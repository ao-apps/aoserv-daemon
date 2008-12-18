package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;

/**
 * Manages HttpdTomcatStdSite version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_3_1 extends HttpdTomcatStdSiteManager_3_X<TomcatCommon_3_1> {

    HttpdTomcatStdSiteManager_3_1(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    public TomcatCommon_3_1 getTomcatCommon() {
        return TomcatCommon_3_1.getInstance();
    }
}
