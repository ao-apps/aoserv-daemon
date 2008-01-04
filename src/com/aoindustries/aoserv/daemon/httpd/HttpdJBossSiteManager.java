package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdJBossSite;

/**
 * Manages HttpdJBossSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HttpdJBossSiteManager extends HttpdTomcatSiteManager {

    final private HttpdJBossSite jbossSite;
    
    public HttpdJBossSiteManager(HttpdJBossSite jbossSite) {
        super(jbossSite.getHttpdTomcatSite());
        this.jbossSite = jbossSite;
    }
}
