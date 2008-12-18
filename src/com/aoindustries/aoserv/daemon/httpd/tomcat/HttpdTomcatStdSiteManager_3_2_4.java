package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;

/**
 * Manages HttpdTomcatStdSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_3_2_4 extends HttpdTomcatStdSiteManager_3_X<TomcatCommon_3_2_4> {

    HttpdTomcatStdSiteManager_3_2_4(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    public TomcatCommon_3_2_4 getTomcatCommon() {
        return TomcatCommon_3_2_4.getInstance();
    }
}
