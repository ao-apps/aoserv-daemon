package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_3_2_4 extends HttpdSharedTomcatManager_3_X<TomcatCommon_3_2_4> {

    HttpdSharedTomcatManager_3_2_4(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
    
    TomcatCommon_3_2_4 getTomcatCommon() {
        return TomcatCommon_3_2_4.getInstance();
    }
}
