package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;

/**
 * Manages HttpdTomcatStdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HttpdTomcatStdSiteManager extends HttpdTomcatSiteManager {

    final private HttpdTomcatStdSite tomcatStdSite;
    
    public HttpdTomcatStdSiteManager(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite.getHttpdTomcatSite());
        this.tomcatStdSite = tomcatStdSite;
    }
}
