package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;

/**
 * Manages HttpdTomcatSharedSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_3_2_4 extends HttpdTomcatSharedSiteManager_3_X<TomcatCommon_3_2_4> {

    HttpdTomcatSharedSiteManager_3_2_4(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    public TomcatCommon_3_2_4 getTomcatCommon() {
        return TomcatCommon_3_2_4.getInstance();
    }
}
