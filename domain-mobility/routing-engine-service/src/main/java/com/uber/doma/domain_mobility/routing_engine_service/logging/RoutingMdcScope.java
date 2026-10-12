package com.uber.doma.domain_mobility.routing_engine_service.logging;

import org.slf4j.MDC;

public class RoutingMdcScope implements AutoCloseable {
    public static final String ROUTE_QUERY_KEY = "routeQueryId";

    public RoutingMdcScope(String routeQueryId) {
        MDC.put(ROUTE_QUERY_KEY, routeQueryId);
    }

    @Override
    public void close() {
        MDC.remove(ROUTE_QUERY_KEY);
    }
}
