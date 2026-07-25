package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.RequestLogEntry;

import java.util.List;

/** F8.4 / the console's request explorer screen: the most recent request_log rows, newest first. */
public interface RequestLogQueryPort {
    List<RequestLogEntry> recent(int limit);
}
