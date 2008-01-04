package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdTomcatStdSite version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdTomcatStdSiteManager_3_1 extends HttpdTomcatStdSiteManager {

    public HttpdTomcatStdSiteManager_3_1(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
