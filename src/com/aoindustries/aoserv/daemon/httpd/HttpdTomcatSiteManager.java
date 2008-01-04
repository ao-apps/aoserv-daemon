package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSite;

/**
 * Manages HttpdTomcatSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HttpdTomcatSiteManager extends HttpdSiteManager {

    final private HttpdTomcatSite tomcatSite;
    
    public HttpdTomcatSiteManager(HttpdTomcatSite tomcatSite) {
        super(tomcatSite.getHttpdSite());
        this.tomcatSite = tomcatSite;
    }
}
