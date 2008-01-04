package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdStaticSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdStaticSite configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdStaticSiteManager extends HttpdSiteManager {

    final private HttpdStaticSite staticSite;
    
    public HttpdStaticSiteManager(HttpdStaticSite staticSite) {
        super(staticSite.getHttpdSite());
        this.staticSite = staticSite;
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
