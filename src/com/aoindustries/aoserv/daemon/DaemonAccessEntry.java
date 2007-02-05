package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */

/**
 * @see  AOServDaemonServer#getDaemonAccessEntry
 *
 * @author  AO Industries, Inc.
 */
public class DaemonAccessEntry {

    public final long key;
    public final int command;
    public final String param1;
    public final String param2;
    public final String param3;
    public final long created;

    public DaemonAccessEntry(
        long key,
        int command,
        String param1,
        String param2,
        String param3
    ) {
        this.key=key;
        this.command=command;
        this.param1=param1;
        this.param2=param2;
        this.param3=param3;
        this.created=System.currentTimeMillis();
    }
}