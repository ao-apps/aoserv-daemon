package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_3_1 extends HttpdSharedTomcatManager_3_X {

    HttpdSharedTomcatManager_3_1(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon getTomcatCommon() {
        return TomcatCommon_3_1.getInstance();
    }
}
