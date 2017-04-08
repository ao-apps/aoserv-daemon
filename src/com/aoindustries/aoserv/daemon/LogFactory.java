/*
 * Copyright 2009-2013, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.TicketCategory;
import com.aoindustries.aoserv.client.TicketLoggingHandler;
import com.aoindustries.util.ErrorPrinter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Provides static access to the logging facilities.  The logs are written
 * into the AOServ ticket system under the type "logs".
 *
 * <p>
 * TODO: Configure this in logging.properties and use typical static loggers per class.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public class LogFactory {

    private static final ConcurrentMap<String,Logger> loggers = new ConcurrentHashMap<>();

    private LogFactory() {
    }

    /**
     * Gets the logger for the provided class.
     */
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    /**
     * <p>
     * Gets the logger for the provided and name.  The logger is cached.
     * Subsequent calls to this method will return the previously created logger.
     * If an error occurs while creating the logger it will return the default
     * logger.  In this case, it will not add the logger to the cache,
     * which will cause it to try again until a fully functional logger is
     * available.
     * </p>
     * <p>
     * Callers of this class should request a logger each time they need one
     * and not cache/reuse the logger provided by this method.  This allows
     * for the automatic retry on logger creation.
     * </p>
     */
    public static Logger getLogger(String name) {
        Logger logger = loggers.get(name);
        if(logger==null) {
            Handler handler;
            try {
                AOServConnector connector = AOServDaemon.getConnector();
                TicketCategory category = connector.getTicketCategories().getTicketCategoryByDotPath("aoserv.aoserv_daemon");
                handler = TicketLoggingHandler.getHandler(
                    AOServDaemonConfiguration.getServerHostname(),
                    connector,
                    category
                );
            } catch(Exception err) {
                ErrorPrinter.printStackTraces(err);
                handler = null;
            }
            logger = Logger.getLogger(name);
            if(handler!=null) {
                synchronized(logger) {
                    boolean foundHandler = false;
                    for(Handler oldHandler : logger.getHandlers()) {
                        if(oldHandler==handler) foundHandler = true;
                        else logger.removeHandler(oldHandler);
                    }
                    if(!foundHandler) logger.addHandler(handler);
                    logger.setUseParentHandlers(false);
                }
                loggers.put(name, logger);
            }
        }
        return logger;
    }
}
