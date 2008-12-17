package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_5_5_X extends HttpdSharedTomcatManager {

    HttpdSharedTomcatManager_5_5_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon getTomcatCommon() {
        return TomcatCommon_5_5_X.getInstance();
    }
}
