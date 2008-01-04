package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;

/**
 * Manages HttpdTomcatSharedSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HttpdTomcatSharedSiteManager extends HttpdTomcatSiteManager {

    final private HttpdTomcatSharedSite tomcatSharedSite;
    
    public HttpdTomcatSharedSiteManager(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite.getHttpdTomcatSite());
        this.tomcatSharedSite = tomcatSharedSite;
    }
}
