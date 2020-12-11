/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.identityoauth.internal;

import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.observation.ObservationManager;
import org.xwiki.rendering.converter.ConversionException;
import org.xwiki.rendering.converter.Converter;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.renderer.printer.WikiPrinter;
import org.xwiki.rendering.syntax.Syntax;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xwiki.identityoauth.IdentityOAuthException;
import com.xwiki.identityoauth.IdentityOAuthManager;
import com.xwiki.identityoauth.IdentityOAuthProvider;

/**
 * Set of methods accessible to the scripts using the IdentityOAuth functions. The manager is the entry point for the
 * set of classes of the IdentityOAuth functions.
 * <p>
 * Just as other classes of this package, it is initialized as a component and thus injected with environment objects
 * such as the logger or context-provider. It is then started at its first invocation and starts the connected
 * components. This class, exposed through its interface {@IdentityOAuthManager} and the authenticator functions in
 * {@IdentityOAuthAuthServiceImpl} is the only one exposing public APIs.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@Singleton
public class IdentityOAuthManagerImpl
        implements IdentityOAuthManager, Initializable, Disposable, IdentityOAuthConstants
{
    // initialisation state

    private LifeCycle lifeCycleState = LifeCycle.CONSTRUCTED;

    // own components
    @Inject
    private Provider<IdentityOAuthXWikiObjects> ioXWikiObjects;

    @Inject
    private Provider<IdentityOAuthAuthService> authServiceProvider;

    // ------ services from the environment
    @Inject
    private Provider<XWikiContext> xwikiContextProvider;

    @Inject
    private Logger log;

    @Inject
    private ComponentManager componentManager;

    @Inject
    private Converter converter;

    @Inject
    private Provider<IdentityOAuthEventListener> eventListener;

    @Inject
    private Provider<ObservationManager> observationManager;

    // -------------------------------------------------
    private Map<String, IdentityOAuthProvider> providers = new HashMap<>();

    private List<String> providersLoginCodes = new ArrayList<>();

    private List<Syntax> providersLoginCodesSyntax = new ArrayList<>();

    private boolean listenerStarted;

    @Override
    public void initialize()
    {
        log.info("IdentityOAuthManagerImpl initializing.");

        updateLifeCycle(LifeCycle.INITIALIZED);
    }

    private void updateLifeCycle(LifeCycle lf)
    {
        log.debug("Lifecycle to " + lf);
        lifeCycleState = lf;
    }

    private void startIfNeedBe()
    {
        if (lifeCycleState == LifeCycle.RUNNING) {
            return;
        }
        if (lifeCycleState != LifeCycle.INITIALIZED) {
            throw new IllegalStateException("Can't start when in state " + lifeCycleState + "!");
        }
        updateLifeCycle(LifeCycle.STARTING);
        boolean failed = false;

        try {
            log.debug("Starting...");
            reloadConfig();
            tryInittingAuthService();
        } catch (Exception e) {
            e.printStackTrace();
            failed = true;
        }

        if (!failed) {
            log.info("IdentityOAuthManagerImpl is now running.");
            updateLifeCycle(LifeCycle.RUNNING);
        } else {
            updateLifeCycle(LifeCycle.INITIALIZED);
        }
    }

    void tryInittingAuthService()
    {
        XWiki xwiki = getXWiki();
        if (xwiki != null) {
            log.debug("Initting authService.");
            // We do not verify with the context if the plugin is active and if the license is active
            // this will be done by the IdentityOAuthAuthService and UI pages later on, when it is called
            // within a request
            try {
                xwiki.setAuthService(authServiceProvider.get());
                log.debug("Succeeded initting authService,");
            } catch (Exception e) {
                log.warn("Failed initting authService", e);
            }
        }
        if (authServiceProvider == null) {
            log.debug("Not yet initting authService.");
        }
    }

    // ==================================================================================

    /**
     * Note that this dispose() will get called when this Extension is uninstalled which is the use case we want to
     * serve. The fact that it'll also be called when XWiki stops is a side effect that is ok.
     */
    @Override
    public void dispose()
    {
        updateLifeCycle(LifeCycle.STOPPING);
        XWiki xwiki = getXWiki();
        // XWiki can be null in the case when XWiki has been started and not accessed (no first request done and thus
        // no XWiki object initialized) and then stopped.
        if (xwiki != null) {
            // Unset the Authentication Service (next time XWiki.getAuthService() is called it'll be re-initialized)
            xwiki.setAuthService(null);
        }
        updateLifeCycle(LifeCycle.STOPPED);
    }

    /**
     * Initializes if need be. The context is extracted from the thead-local.
     *
     * @since 1.0
     */
    public void init()
    {
        startIfNeedBe();
        if (lifeCycleState == LifeCycle.CONSTRUCTED) {
            initialize();
        }
    }

    private XWiki getXWiki()
    {
        XWiki result = null;
        XWikiContext xc = this.xwikiContextProvider.get();
        // XWikiContext could be null at startup when the Context Provider has not been initialized yet (it's
        // initialized after the first request).
        if (xc != null) {
            result = xc.getWiki();
        }
        return result;
    }

    void rebuildProviders()
    {
        IdentityOAuthXWikiObjects iox = ioXWikiObjects.get();

        List<ProviderConfig> providerConfigs = iox.loadProviderConfigs();
        providerConfigs.sort(new Comparator<ProviderConfig>()
        {
            @Override public int compare(ProviderConfig o1, ProviderConfig o2)
            {
                return Integer.compare(o1.getOrderHint(), o2.getOrderHint());
            }
        });
        int lastScore = Integer.MIN_VALUE;
        providersLoginCodes.clear();
        providersLoginCodesSyntax.clear();
        for (ProviderConfig config : providerConfigs) {
            try {
                IdentityOAuthProvider pr = componentManager.getInstance(
                        IdentityOAuthProvider.class, config.getName());
                pr.setProviderName(config.getName());
                pr.initialize(config.getConfig());
                providers.put(config.getName(), pr);
                // insert XWIKI at position zero
                if (config.getOrderHint() > 0 && lastScore <= 0) {
                    providersLoginCodes.add(XWIKILOGIN);
                    providersLoginCodesSyntax.add(Syntax.XWIKI_2_1);
                }
                providersLoginCodes.add(config.getLoginCode().replaceAll("-PROVIDER-", config.getName()));
                providersLoginCodesSyntax.add(config.getProviderDocumentSyntax());
                lastScore = config.getOrderHint();
            } catch (Exception e) {
                log.warn("Trouble at creating provider \"" + config.getName() + "\":", e);
            }
        }
        if (lastScore <= 0) {
            providersLoginCodes.add(XWIKILOGIN);
            providersLoginCodesSyntax.add(Syntax.XWIKI_2_1);
        }
    }

    /**
     * Performs XWiki rendering and transformation on the loginCodes of each provider.
     *
     * @return a list of rendered code in XHTML.
     */
    public List<String> renderLoginCodes()
    {
        List<String> renderedLoginCodes = new ArrayList<>(providersLoginCodes.size());
        for (int i = 0; i < providersLoginCodes.size(); i++) {
            try {
                String loginCode = providersLoginCodes.get(i);
                if (XWIKILOGIN.equals(loginCode)) {
                    renderedLoginCodes.add(loginCode);
                } else {
                    // Convert input in XWiki Syntax 2.1 into XHTML. The result is stored in the printer.
                    WikiPrinter printer = new DefaultWikiPrinter();
                    converter.convert(new StringReader(loginCode),
                            providersLoginCodesSyntax.get(i), Syntax.XHTML_1_0, printer);
                    renderedLoginCodes.add(printer.toString());
                }
            } catch (ConversionException e) {
                renderedLoginCodes.add("BROKEN RENDERING");
                log.warn("Can't render (BROKEN RENDERING): ", e);
            }
        }
        return renderedLoginCodes;
    }

    /**
     * Reloads the configuration from the wiki-objects.
     */
    public void reloadConfig()
    {
        log.info("Reloading config.");
        if (eventListener != null && !listenerStarted) {
            log.debug("Registering observation manager.");
            listenerStarted = true;
            observationManager.get().addListener(eventListener.get());
        }
        rebuildProviders();
    }

    /**
     * Uses the request and response objects to read the providerName and invoke the named provider to trigger the start
     * of an authorization dialog by redirecting the user to the authorization dialog. The browser will be taken back to
     * the same page after the authorization.
     *
     * @return if the app was able to request an authorization URL and redirect the browser
     * @throws IdentityOAuthException if a communication problem with the other components occured
     * @since 1.0
     */
    public boolean processOAuthStart() throws IdentityOAuthException
    {
        try {
            log.debug("OAuthStart.");
            HttpServletRequest request = xwikiContextProvider.get().getRequest();
            String providerName = request.getParameter(PROVIDER);
            IdentityOAuthProvider provider = providers.get(providerName);
            String oauthBackPage = request.getParameter("browserLocation");
            String redirectUrl = provider.getRemoteAuthorizationUrl(oauthBackPage);

            // populate sessionInfo with fresh data
            getSessionInfo().clear(providerName);
            String xredirect = request.getParameter("xredirect");
            if (xredirect == null) {
                // xredirect will be the home
                xredirect = oauthBackPage.replace("/(login/XWiki/XWikiLogin).*", "/");
            }
            getSessionInfo().setProviderAuthorizationRunning(providerName);

            log.debug("Redirecting to authorization URL.");
            URL oauthBackPageU = new URL(oauthBackPage);
            URL xredirectU = new URL(oauthBackPageU, xredirect);

            // only keep xredirect if it matches the browser's location
            if (xredirectU.getProtocol().equals(oauthBackPageU.getProtocol())
                    && xredirectU.getHost().equals(oauthBackPageU.getHost())
                    && xredirectU.getPort() == oauthBackPageU.getPort())
            {
                getSessionInfo().setXredirect(xredirect);
            }

            log.debug("OAuthStart will redirect.");
            xwikiContextProvider.get().getResponse().sendRedirect(redirectUrl);
            return true;
        } catch (Exception ex) {
            log.warn("Trouble at authorizing", ex);
            return false;
        }
    }

    /**
     * Inspects the request to detect if an OAuth return needs to be processed.
     *
     * @return true if the relevant information (cookie, parameter, ...) is found.
     */
    public boolean doesDetectReturn()
    {
        return getSessionInfo().getProviderAuthorizationRunning() != null;
    }

    /**
     * Performs the necessary communication with the OAuth provider to fetch identity and update the XWiki-user.
     *
     * @return "failed login" if failed, {@NOUSER}, or "ok" if successful
     * @since 1.0
     */
    public String processOAuthReturn()
    {
        try {
            log.debug("Return OAuth.");
            // collect provider-name (an OAuth return is single-use)
            String providerName = getSessionInfo().getProviderAuthorizationRunning();
            getSessionInfo().setProviderAuthorizationRunning(null);
            IdentityOAuthProvider provider = providers.get(providerName);
            String authorization =
                    provider.readAuthorizationFromReturn(xwikiContextProvider.get().getRequest().getParameterMap());
            Pair<String, Date> token = provider.createToken(authorization);

            // store auth and token
            IdentityOAuthSessionInfo sessionInfo = getSessionInfo();
            sessionInfo.setAuthorizationCode(providerName, authorization);
            sessionInfo.setToken(providerName, token.getLeft());
            sessionInfo.setTokenExpiry(providerName, token.getRight());

            IdentityOAuthProvider.IdentityDescription id = provider.fetchIdentityDetails(token.getLeft());
            String xwikiUser = ioXWikiObjects.get().updateXWikiUser(id, provider);

            // login at next call to the authenticator (the next http request)
            sessionInfo.setUserToLogIn(xwikiUser);
            log.debug("User will be logged-in.");

            // process redirect
            // getSessionInfo().xredirect is guaranteed to be not null in processOAuthStart
            xwikiContextProvider.get().getResponse().sendRedirect(getSessionInfo().pickXredirect());
            log.debug("Redirecting user to originally intended URL.");
        } catch (Exception e) {
            log.warn("Trouble at processing OAuth return", e);
            return FAILEDLOGIN;
        }
        return "ok";
    }

    @Override
    public boolean hasSessionIdentityInfo(String provider)
    {
        return getSessionInfo().getAuthorizationCode(provider) != null;
    }

    private IdentityOAuthSessionInfo getSessionInfo()
    {
        return IdentityOAuthSessionInfo.getFromSession(xwikiContextProvider.get().getRequest());
    }

    @Override
    public IdentityOAuthProvider getProvider(String name)
    {
        return providers.get(name);
    }

    enum LifeCycle
    { CONSTRUCTED, INITIALIZED, STARTING, RUNNING, STOPPING, STOPPED }
}


