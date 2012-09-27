package com.rackspace.papi.components.clientauth;

import com.rackspace.papi.components.clientauth.config.ClientAuthConfig;
import com.rackspace.papi.filter.FilterConfigHelper;
import com.rackspace.papi.filter.logic.impl.FilterLogicHandlerDelegate;
import com.rackspace.papi.service.config.ConfigurationService;
import com.rackspace.papi.service.context.ContextAdapter;
import com.rackspace.papi.service.context.ServletContextHelper;
import com.rackspace.papi.service.datastore.Datastore;
import com.rackspace.papi.service.datastore.DatastoreService;
import java.io.IOException;
import javax.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jhopper
 */
public class ClientAuthenticationFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAuthenticationFilter.class);
    private static final String DEFAULT_CONFIG = "client-auth-n.cfg.xml";
    private String config;
    private ClientAuthenticationHandlerFactory handlerFactory;
    private ConfigurationService configurationManager;

    @Override
    public void destroy() {
        configurationManager.unsubscribeFrom(config, handlerFactory);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        new FilterLogicHandlerDelegate(request, response, chain).doFilter(handlerFactory.newHandler());
    }

    private Datastore getDatastore(DatastoreService datastoreService) {
        return datastoreService.defaultDatastore().getDatastore();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        final ContextAdapter ctx = ServletContextHelper.getInstance().getPowerApiContext(filterConfig.getServletContext());

        config = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG);
        LOG.info("Initializing filter using config " + config);
        handlerFactory = new ClientAuthenticationHandlerFactory(getDatastore(ctx.datastoreService()));
        configurationManager = ctx.configurationService();
        configurationManager.subscribeTo(config, handlerFactory, ClientAuthConfig.class);
    }
}
