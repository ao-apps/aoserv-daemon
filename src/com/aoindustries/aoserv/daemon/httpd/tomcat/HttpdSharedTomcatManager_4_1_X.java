package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 4.1.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_4_1_X extends HttpdSharedTomcatManager {

    HttpdSharedTomcatManager_4_1_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon getTomcatCommon() {
        return TomcatCommon_4_1_X.getInstance();
    }
}
