// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import com.jetbrains.cef.JCefAppConfig;
import com.jetbrains.cef.JdkEx;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserFactory;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefRequestContext;
import org.cef.browser.CefRendering;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefDragData;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.callback.CefPrintDialogCallback;
import org.cef.callback.CefPrintJobCallback;
import org.cef.callback.CefMediaAccessCallback;
import org.cef.handler.CefClientHandler;
import org.cef.handler.CefContextMenuHandler;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefDragHandler;
import org.cef.handler.CefFocusHandler;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefKeyboardHandler;
import org.cef.handler.CefLifeSpanHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefPrintHandler;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefScreenInfo;
import org.cef.handler.CefWindowHandler;
import org.cef.handler.CefMediaAccessHandler;
import org.cef.misc.BoolRef;
import org.cef.misc.CefPrintSettings;
import org.cef.misc.CefLog;
import org.cef.network.CefRequest;
import org.cef.network.CefRequest.TransitionType;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FocusTraversalPolicy;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Vector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.SwingUtilities;

/**
 * Client that owns a browser and renderer.
 */
public class CefClient extends CefClientHandler
        implements CefContextMenuHandler, CefDialogHandler, CefDisplayHandler, CefDownloadHandler,
                   CefDragHandler, CefFocusHandler, CefMediaAccessHandler, CefJSDialogHandler, CefKeyboardHandler,
                   CefLifeSpanHandler, CefLoadHandler, CefPrintHandler, CefRenderHandler,
                   CefRequestHandler, CefWindowHandler {
    private static final boolean TRACE_LIFESPAN = Boolean.getBoolean("trace.client.lifespan");
    private static final boolean ADD_EMPTY_ROUTER = !Boolean.getBoolean("jcef.client.dont_add_empty_router"); // workaround for IDEA259472Test
    private final ConcurrentHashMap<Integer, CefBrowser> browser_ = new ConcurrentHashMap<Integer, CefBrowser>();
    private CefContextMenuHandler contextMenuHandler_ = null;
    private CefDialogHandler dialogHandler_ = null;
    private CefDisplayHandler displayHandler_ = null;
    private CefDownloadHandler downloadHandler_ = null;
    private CefDragHandler dragHandler_ = null;
    private CefFocusHandler focusHandler_ = null;
    private CefMediaAccessHandler mediaAccessHandler_ = null;
    private CefJSDialogHandler jsDialogHandler_ = null;
    private CefKeyboardHandler keyboardHandler_ = null;
    private final List<CefLifeSpanHandler> lifeSpanHandlers_ = new ArrayList<>();
    private CefLoadHandler loadHandler_ = null;
    private CefPrintHandler printHandler_ = null;
    private CefRequestHandler requestHandler_ = null;
    private boolean isDisposed_ = false;
    private volatile CefBrowser focusedBrowser_ = null;
    private final PropertyChangeListener propertyChangeListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (focusedBrowser_ != null) {
                Component browserUI = focusedBrowser_.getUIComponent();
                if (browserUI == null) return;
                Object oldUI = evt.getOldValue();
                if (isPartOf(oldUI, browserUI)) {
                    focusedBrowser_.setFocus(false);
                    focusedBrowser_ = null;
                }
            }
        }
    };

    // workaround for JBR-4176 Some tests fail after JCEF update
    // Empiric observation: some client handlers may be ignored for CEF version 95 (and later) without adding at least one router
    // TODO: remove when DisplayHandlerTest (or IDEA259472Test) starts running with empty list of message routers
    private CefMessageRouter myEmptyRouter;

    /**
     * The CTOR is only accessible within this package.
     * Use CefApp.createClient() to create an instance of
     * this class.
     *
     * @see org.cef.CefApp#createClient()
     */
    CefClient() throws UnsatisfiedLinkError {
        super();

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(
                propertyChangeListener);

        if (ADD_EMPTY_ROUTER) addMessageRouter(myEmptyRouter = CefMessageRouter.create());
    }

    private boolean isPartOf(Object obj, Component browserUI) {
        if (obj == browserUI) return true;
        if (obj instanceof Container) {
            Component childs[] = ((Container) obj).getComponents();
            for (Component child : childs) {
                return isPartOf(child, browserUI);
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        isDisposed_ = true;
        cleanupBrowser(-1);

        if (myEmptyRouter != null) {
            removeMessageRouter(myEmptyRouter);
            myEmptyRouter.dispose();
            myEmptyRouter = null;
        }
    }

    // CefClientHandler

    /**
     * @deprecated {@link #createBrowser(String, CefRendering, boolean)}
     */
    @Deprecated
    public CefBrowser createBrowser(
            String url, boolean isOffscreenRendered, boolean isTransparent) {
        return createBrowser(url, isOffscreenRendered, isTransparent, null);
    }


    /**
     * @deprecated {@link #createBrowser(String, CefRendering, boolean, CefRequestContext)}
     */
    @Deprecated
    public CefBrowser createBrowser(String url, boolean isOffscreenRendered, boolean isTransparent,
                                    CefRequestContext context) {
        return createBrowser(url, isOffscreenRendered ? CefRendering.OFFSCREEN : CefRendering.DEFAULT, isTransparent, context);
    }

    public CefBrowser createBrowser(String url, CefRendering rendering, boolean isTransparent) {
        return createBrowser(url, rendering, isTransparent, null);
    }

    public CefBrowser createBrowser(String url, CefRendering rendering, boolean isTransparent,
                                    CefRequestContext context) {
        if (isDisposed_)
            throw new IllegalStateException("Can't create browser. CefClient is disposed");
        return CefBrowserFactory.create(this, url, rendering, isTransparent, context);
    }

    @Override
    protected CefBrowser getBrowser(int identifier) {
        return browser_.get(identifier);
    }

    @Override
    protected Object[] getAllBrowser() {
        return browser_.values().toArray();
    }

    @Override
    protected CefContextMenuHandler getContextMenuHandler() {
        return this;
    }

    @Override
    protected CefDialogHandler getDialogHandler() {
        return this;
    };

    @Override
    protected CefDisplayHandler getDisplayHandler() {
        return this;
    }

    @Override
    protected CefDownloadHandler getDownloadHandler() {
        return this;
    }

    @Override
    protected CefDragHandler getDragHandler() {
        return this;
    }

    @Override
    protected CefFocusHandler getFocusHandler() {
        return this;
    }

    @Override
    protected CefMediaAccessHandler getMediaAccessHandler() { return this; }

    @Override
    protected CefJSDialogHandler getJSDialogHandler() {
        return this;
    }

    @Override
    protected CefKeyboardHandler getKeyboardHandler() {
        return this;
    }

    @Override
    protected CefLifeSpanHandler getLifeSpanHandler() {
        return this;
    }

    @Override
    protected CefLoadHandler getLoadHandler() {
        return this;
    }

    @Override
    protected CefPrintHandler getPrintHandler() {
        return this;
    }

    @Override
    protected CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefRequestHandler getRequestHandler() {
        return this;
    }

    @Override
    protected CefWindowHandler getWindowHandler() {
        return this;
    }

    // CefContextMenuHandler

    public CefClient addContextMenuHandler(CefContextMenuHandler handler) {
        if (contextMenuHandler_ == null) contextMenuHandler_ = handler;
        return this;
    }

    public void removeContextMenuHandler() {
        contextMenuHandler_ = null;
    }

    @Override
    public void onBeforeContextMenu(
            CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
        if (contextMenuHandler_ != null && browser != null)
            contextMenuHandler_.onBeforeContextMenu(browser, frame, params, model);
    }

    @Override
    public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame,
            CefContextMenuParams params, int commandId, int eventFlags) {
        if (contextMenuHandler_ != null && browser != null)
            return contextMenuHandler_.onContextMenuCommand(
                    browser, frame, params, commandId, eventFlags);
        return false;
    }

    @Override
    public void onContextMenuDismissed(CefBrowser browser, CefFrame frame) {
        if (contextMenuHandler_ != null && browser != null)
            contextMenuHandler_.onContextMenuDismissed(browser, frame);
    }

    // CefDialogHandler

    public CefClient addDialogHandler(CefDialogHandler handler) {
        if (dialogHandler_ == null) dialogHandler_ = handler;
        return this;
    }

    public void removeDialogHandler() {
        dialogHandler_ = null;
    }

    @Override
    public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
            String defaultFilePath, Vector<String> acceptFilters, int selectedAcceptFilter,
            CefFileDialogCallback callback) {
        if (dialogHandler_ != null && browser != null) {
            return dialogHandler_.onFileDialog(browser, mode, title, defaultFilePath, acceptFilters,
                    selectedAcceptFilter, callback);
        }
        return false;
    }

    // CefDisplayHandler

    public CefClient addDisplayHandler(CefDisplayHandler handler) {
        if (displayHandler_ == null) displayHandler_ = handler;
        return this;
    }

    public void removeDisplayHandler() {
        displayHandler_ = null;
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onAddressChange(browser, frame, url);
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        if (displayHandler_ != null && browser != null)
            displayHandler_.onTitleChange(browser, title);
    }

    @Override
    public boolean onTooltip(CefBrowser browser, String text) {
        if (displayHandler_ != null && browser != null) {
            return displayHandler_.onTooltip(browser, text);
        }
        return false;
    }

    @Override
    public void onStatusMessage(CefBrowser browser, String value) {
        if (displayHandler_ != null && browser != null) {
            displayHandler_.onStatusMessage(browser, value);
        }
    }

    @Override
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
            String message, String source, int line) {
        if (displayHandler_ != null && browser != null) {
            return displayHandler_.onConsoleMessage(browser, level, message, source, line);
        }
        return false;
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        if (browser == null) {
            return false;
        }

        if (displayHandler_ != null && displayHandler_.onCursorChange(browser, cursorType)) {
            return true;
        }

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) {
            return realHandler.onCursorChange(browser, cursorType);
        }

        return false;
    }

    // CefDownloadHandler

    public CefClient addDownloadHandler(CefDownloadHandler handler) {
        if (downloadHandler_ == null) downloadHandler_ = handler;
        return this;
    }

    public void removeDownloadHandler() {
        downloadHandler_ = null;
    }

    @Override
    public void onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem,
            String suggestedName, CefBeforeDownloadCallback callback) {
        if (downloadHandler_ != null && browser != null)
            downloadHandler_.onBeforeDownload(browser, downloadItem, suggestedName, callback);
    }

    @Override
    public void onDownloadUpdated(
            CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
        if (downloadHandler_ != null && browser != null)
            downloadHandler_.onDownloadUpdated(browser, downloadItem, callback);
    }

    // CefDragHandler

    public CefClient addDragHandler(CefDragHandler handler) {
        if (dragHandler_ == null) dragHandler_ = handler;
        return this;
    }

    public void removeDragHandler() {
        dragHandler_ = null;
    }

    @Override
    public boolean onDragEnter(CefBrowser browser, CefDragData dragData, int mask) {
        if (dragHandler_ != null && browser != null)
            return dragHandler_.onDragEnter(browser, dragData, mask);
        return false;
    }

    // CefFocusHandler

    public CefClient addFocusHandler(CefFocusHandler handler) {
        if (focusHandler_ == null) focusHandler_ = handler;
        return this;
    }

    public void removeFocusHandler() {
        focusHandler_ = null;
    }

    @Override
    public void onTakeFocus(CefBrowser browser, boolean next) {
        if (browser == null) return;

        browser.setFocus(false);
        Component uiComponent = browser.getUIComponent();
        if (uiComponent == null) return;
        Container parent = uiComponent.getParent();
        if (parent != null) {
            FocusTraversalPolicy policy = null;
            while (parent != null) {
                policy = parent.getFocusTraversalPolicy();
                if (policy != null) break;
                parent = parent.getParent();
            }
            if (policy != null) {
                Component nextComp = next
                        ? policy.getComponentAfter(parent, uiComponent)
                        : policy.getComponentBefore(parent, uiComponent);
                if (nextComp == null) {
                    policy.getDefaultComponent(parent).requestFocus();
                } else {
                    nextComp.requestFocus();
                }
            }
        }
        focusedBrowser_ = null;
        if (focusHandler_ != null) focusHandler_.onTakeFocus(browser, next);
    }

    @Override
    public boolean onSetFocus(final CefBrowser browser, FocusSource source) {
        if (browser == null) return false;

        Boolean alreadyHandled = Boolean.FALSE;
        if (focusHandler_ != null) {
            Component uiComponent = browser.getUIComponent();
            if (uiComponent == null) return true;
            alreadyHandled = JdkEx.invokeOnEDTAndWait(() -> focusHandler_.onSetFocus(browser, source), Boolean.TRUE /*ignore focus*/, uiComponent);
        }
        return alreadyHandled;
    }

    @Override
    public void onGotFocus(CefBrowser browser) {
        if (browser == null) return;
        if (focusedBrowser_ == browser) return; // prevent recursive call (in OSR)

        focusedBrowser_ = browser;
        browser.setFocus(true);
        if (focusHandler_ != null) {
            Component uiComponent = browser.getUIComponent();
            if (uiComponent == null ) return;
            JdkEx.invokeOnEDTAndWait(() -> focusHandler_.onGotFocus(browser), uiComponent);
        }
    }

    // CefMediaAccessHandler

    public CefClient addMediaAccessHandler(CefMediaAccessHandler handler) {
        if (mediaAccessHandler_ == null) mediaAccessHandler_ = handler;
        return this;
    }

    public void removeMediaAccessHandler() {
        mediaAccessHandler_ = null;
    }

    @Override
    public boolean onRequestMediaAccessPermission(
            CefBrowser browser,
            CefFrame frame,
            String requesting_url,
            int requested_permissions,
            CefMediaAccessCallback callback) {
        if (mediaAccessHandler_ != null && browser != null)
            return mediaAccessHandler_.onRequestMediaAccessPermission(browser, frame, requesting_url,
                    requested_permissions, callback);
        return false;
    }

    // CefJSDialogHandler

    public CefClient addJSDialogHandler(CefJSDialogHandler handler) {
        if (jsDialogHandler_ == null) jsDialogHandler_ = handler;
        return this;
    }

    public void removeJSDialogHandler() {
        jsDialogHandler_ = null;
    }

    @Override
    public boolean onJSDialog(CefBrowser browser, String origin_url, JSDialogType dialog_type,
            String message_text, String default_prompt_text, CefJSDialogCallback callback,
            BoolRef suppress_message) {
        if (jsDialogHandler_ != null && browser != null)
            return jsDialogHandler_.onJSDialog(browser, origin_url, dialog_type, message_text,
                    default_prompt_text, callback, suppress_message);
        return false;
    }

    @Override
    public boolean onBeforeUnloadDialog(CefBrowser browser, String message_text, boolean is_reload,
            CefJSDialogCallback callback) {
        if (jsDialogHandler_ != null && browser != null)
            return jsDialogHandler_.onBeforeUnloadDialog(
                    browser, message_text, is_reload, callback);
        return false;
    }

    @Override
    public void onResetDialogState(CefBrowser browser) {
        if (jsDialogHandler_ != null && browser != null)
            jsDialogHandler_.onResetDialogState(browser);
    }

    @Override
    public void onDialogClosed(CefBrowser browser) {
        if (jsDialogHandler_ != null && browser != null) jsDialogHandler_.onDialogClosed(browser);
    }

    // CefKeyboardHandler

    public CefClient addKeyboardHandler(CefKeyboardHandler handler) {
        if (keyboardHandler_ == null) keyboardHandler_ = handler;
        return this;
    }

    public void removeKeyboardHandler() {
        keyboardHandler_ = null;
    }

    @Override
    public boolean onPreKeyEvent(
            CefBrowser browser, CefKeyEvent event, BoolRef is_keyboard_shortcut) {
        if (keyboardHandler_ != null && browser != null)
            return keyboardHandler_.onPreKeyEvent(browser, event, is_keyboard_shortcut);
        return false;
    }

    @Override
    public boolean onKeyEvent(CefBrowser browser, CefKeyEvent event) {
        if (keyboardHandler_ != null && browser != null)
            return keyboardHandler_.onKeyEvent(browser, event);
        return false;
    }

    // CefLifeSpanHandler

    public CefClient addLifeSpanHandler(CefLifeSpanHandler handler) {
        synchronized (lifeSpanHandlers_) {
            lifeSpanHandlers_.add(handler);
        }
        return this;
    }

    public void removeLifeSpanHandler() {
        synchronized (lifeSpanHandlers_) {
            lifeSpanHandlers_.clear();
        }
    }

    @Override
    public boolean onBeforePopup(
            CefBrowser browser, CefFrame frame, String target_url, String target_frame_name) {
        if (isDisposed_) return true;
        if (browser == null)
            return false;
        synchronized (lifeSpanHandlers_) {
            boolean result = false;
            for (CefLifeSpanHandler lsh: lifeSpanHandlers_) {
                result |= lsh.onBeforePopup(browser, frame, target_url, target_frame_name);
            }
            return result;
        }
    }

    @Override
    public void onAfterCreated(CefBrowser browser) {
        if (browser == null) return;
        if (TRACE_LIFESPAN) CefLog.INSTANCE.debug("CefClient: browser=%s: onAfterCreated", browser);
        boolean disposed = isDisposed_;
        if (disposed) {
            CefLog.INSTANCE.warn("Browser %s was created while CefClient was marked as disposed", browser);
        }

        // keep browser reference
        Integer identifier = browser.getIdentifier();
        if (!disposed) {
            browser_.put(identifier, browser);
        }
        synchronized (lifeSpanHandlers_) {
            for (CefLifeSpanHandler lsh: lifeSpanHandlers_)
                lsh.onAfterCreated(browser);
        }
        if (disposed) {
            // Not sure, but it makes sense to close browser since it's not in browser_
            browser.close(true);
        }
    }

    @Override
    public void onAfterParentChanged(CefBrowser browser) {
        if (browser == null) return;
        synchronized (lifeSpanHandlers_) {
            for (CefLifeSpanHandler lsh: lifeSpanHandlers_)
                lsh.onAfterParentChanged(browser);
        }
    }

    @Override
    public boolean doClose(CefBrowser browser) {
        if (browser == null) return false;
        synchronized (lifeSpanHandlers_) {
            for (CefLifeSpanHandler lsh: lifeSpanHandlers_)
                lsh.doClose(browser);
        }
        return browser.doClose();
    }

    @Override
    public void onBeforeClose(CefBrowser browser) {
        if (browser == null) return;
        if (TRACE_LIFESPAN) CefLog.INSTANCE.debug("CefClient: browser=%s: onBeforeClose", browser);
        synchronized (lifeSpanHandlers_) {
            for (CefLifeSpanHandler lsh: lifeSpanHandlers_)
                lsh.onBeforeClose(browser);
        }
        browser.onBeforeClose();

        // remove browser reference
        cleanupBrowser(browser.getIdentifier());
    }

    private void cleanupBrowser(int identifier) {
        if (identifier >= 0) {
            // Remove the specific browser that closed.
            browser_.remove(identifier);
        } else {
            assert isDisposed_;
            Collection<CefBrowser> browserList = new ArrayList<>(browser_.values());
            if (!browserList.isEmpty()) {
                // Close all browsers.
                // Once any of browsers close, it will invoke #onBeforeClose and #cleanupBrowser
                for (CefBrowser browser : browserList) {
                    browser.close(true);
                }
                return;
            }
        }
        if (!isDisposed_) return;
        if (!browser_.isEmpty()) return;

        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(
                propertyChangeListener);
        removeContextMenuHandler(this);
        removeDialogHandler(this);
        removeDisplayHandler(this);
        removeDownloadHandler(this);
        removeDragHandler(this);
        removeFocusHandler(this);
        removeJSDialogHandler(this);
        removeKeyboardHandler(this);
        removeLifeSpanHandler(this);
        removeLoadHandler(this);
        removePrintHandler(this);
        removeRenderHandler(this);
        removeRequestHandler(this);
        removeWindowHandler(this);
        super.dispose();

        CefApp app = CefApp.getInstanceIfAny();
        if (app != null) app.clientWasDisposed(this);
    }

    // CefLoadHandler

    public CefClient addLoadHandler(CefLoadHandler handler) {
        if (loadHandler_ == null) loadHandler_ = handler;
        return this;
    }

    public void removeLoadHandler() {
        loadHandler_ = null;
    }

    @Override
    public void onLoadingStateChange(
            CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, TransitionType transitionType) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadStart(browser, frame, transitionType);
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadEnd(browser, frame, httpStatusCode);
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
            String errorText, String failedUrl) {
        if (loadHandler_ != null && browser != null)
            loadHandler_.onLoadError(browser, frame, errorCode, errorText, failedUrl);
    }

    // CefPrintHandler

    public CefClient addPrintHandler(CefPrintHandler handler) {
        if (printHandler_ == null) printHandler_ = handler;
        return this;
    }

    public void removePrintHandler() {
        printHandler_ = null;
    }

    @Override
    public void onPrintStart(CefBrowser browser) {
        if (printHandler_ != null && browser != null) printHandler_.onPrintStart(browser);
    }

    @Override
    public void onPrintSettings(
            CefBrowser browser, CefPrintSettings settings, boolean getDefaults) {
        if (printHandler_ != null && browser != null)
            printHandler_.onPrintSettings(browser, settings, getDefaults);
    }

    @Override
    public boolean onPrintDialog(
            CefBrowser browser, boolean hasSelection, CefPrintDialogCallback callback) {
        if (printHandler_ != null && browser != null)
            return printHandler_.onPrintDialog(browser, hasSelection, callback);
        return false;
    }

    @Override
    public boolean onPrintJob(CefBrowser browser, String documentName, String pdfFilePath,
            CefPrintJobCallback callback) {
        if (printHandler_ != null && browser != null)
            return printHandler_.onPrintJob(browser, documentName, pdfFilePath, callback);
        return false;
    }

    @Override
    public void onPrintReset(CefBrowser browser) {
        if (printHandler_ != null && browser != null) printHandler_.onPrintReset(browser);
    }

    @Override
    public Dimension getPdfPaperSize(CefBrowser browser, int deviceUnitsPerInch) {
        if (printHandler_ != null && browser != null)
            return printHandler_.getPdfPaperSize(browser, deviceUnitsPerInch);
        return null;
    }

    // CefMessageRouter

    @Override
    public synchronized void addMessageRouter(CefMessageRouter messageRouter) {
        super.addMessageRouter(messageRouter);
    }

    @Override
    public synchronized void removeMessageRouter(CefMessageRouter messageRouter) {
        super.removeMessageRouter(messageRouter);
    }

    // CefRenderHandler

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        // [tav] resize to 1x1 size to avoid crash in cef
        if (browser == null) return new Rectangle(0, 0, 1, 1);

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) {
            Rectangle rect = realHandler.getViewRect(browser);
            if (rect.width <= 0 || rect.height <= 0) {
                rect = new Rectangle(rect.x, rect.y, 1, 1);
            }
            return rect;
        }
        return new Rectangle(0, 0, 1, 1);
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        if (browser == null) return new Point(0, 0);

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) return realHandler.getScreenPoint(browser, viewPoint);
        return new Point(0, 0);
    }

    @Override
    public double getDeviceScaleFactor(CefBrowser browser) {
        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) {
            return realHandler.getDeviceScaleFactor(browser);
        }
        return JCefAppConfig.getDeviceScaleFactor(browser.getUIComponent());
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.onPopupShow(browser, show);
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.onPopupSize(browser, size);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null)
            realHandler.onPaint(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        if (browser == null) return false;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) return realHandler.startDragging(browser, dragData, mask, x, y);
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (browser == null) return;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) realHandler.updateDragCursor(browser, operation);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        if (browser == null) return false;

        CefRenderHandler realHandler = browser.getRenderHandler();
        if (realHandler != null) return realHandler.getScreenInfo(browser, screenInfo);
        return false;
    }

    // CefRequestHandler

    public CefClient addRequestHandler(CefRequestHandler handler) {
        if (requestHandler_ == null) requestHandler_ = handler;
        return this;
    }

    public void removeRequestHandler() {
        requestHandler_ = null;
    }

    @Override
    public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
            boolean user_gesture, boolean is_redirect) {
        if (requestHandler_ != null && browser != null)
            return requestHandler_.onBeforeBrowse(
                    browser, frame, request, user_gesture, is_redirect);
        return false;
    }

    @Override
    public boolean onOpenURLFromTab(
            CefBrowser browser, CefFrame frame, String target_url, boolean user_gesture) {
        if (isDisposed_) return true;
        if (requestHandler_ != null && browser != null)
            return requestHandler_.onOpenURLFromTab(browser, frame, target_url, user_gesture);
        return false;
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
            CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
            BoolRef disableDefaultHandling) {
        if (requestHandler_ != null && browser != null) {
            return requestHandler_.getResourceRequestHandler(browser, frame, request, isNavigation,
                    isDownload, requestInitiator, disableDefaultHandling);
        }
        return null;
    }

    @Override
    public boolean getAuthCredentials(CefBrowser browser, String origin_url, boolean isProxy,
            String host, int port, String realm, String scheme, CefAuthCallback callback) {
        if (requestHandler_ != null && browser != null)
            return requestHandler_.getAuthCredentials(
                    browser, origin_url, isProxy, host, port, realm, scheme, callback);
        return false;
    }

    @Override
    public boolean onQuotaRequest(
            CefBrowser browser, String origin_url, long new_size, CefCallback callback) {
        if (requestHandler_ != null && browser != null)
            return requestHandler_.onQuotaRequest(browser, origin_url, new_size, callback);
        return false;
    }

    @Override
    public boolean onCertificateError(CefBrowser browser, ErrorCode cert_error, String request_url,
            CefCallback callback) {
        if (requestHandler_ != null)
            return requestHandler_.onCertificateError(browser, cert_error, request_url, callback);
        return false;
    }

    @Override
    public void onPluginCrashed(CefBrowser browser, String pluginPath) {
        if (requestHandler_ != null) requestHandler_.onPluginCrashed(browser, pluginPath);
    }

    @Override
    public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status) {
        if (requestHandler_ != null) requestHandler_.onRenderProcessTerminated(browser, status);
    }

    // CefWindowHandler

    @Override
    public Rectangle getRect(CefBrowser browser) {
        if (browser == null) return new Rectangle(0, 0, 0, 0);

        CefWindowHandler realHandler = browser.getWindowHandler();
        if (realHandler != null) return realHandler.getRect(browser);
        return new Rectangle(0, 0, 0, 0);
    }

    @Override
    public void onMouseEvent(
            CefBrowser browser, int event, int screenX, int screenY, int modifier, int button) {
        if (browser == null) return;

        CefWindowHandler realHandler = browser.getWindowHandler();
        if (realHandler != null)
            realHandler.onMouseEvent(browser, event, screenX, screenY, modifier, button);
    }
}
