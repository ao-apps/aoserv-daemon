package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdSharedTomcatManager_3_X extends HttpdSharedTomcatManager {

    HttpdSharedTomcatManager_3_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }
}
