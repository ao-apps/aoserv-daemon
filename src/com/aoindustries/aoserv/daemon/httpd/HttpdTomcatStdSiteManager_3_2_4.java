package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdTomcatStdSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdTomcatStdSiteManager_3_2_4 extends HttpdTomcatStdSiteManager {

    public HttpdTomcatStdSiteManager_3_2_4(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}
