package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import java.io.*;

/**
 * Encapsulates the output of the /bin/netstat -ln command.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatLN {

    final public String[] deviceIDs;
    final public int[] listens;

    public NetStatLN() {
        deviceIDs=new String[0];
        listens=new int[0];
    }
}