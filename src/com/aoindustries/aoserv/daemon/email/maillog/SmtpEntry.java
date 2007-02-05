package com.aoindustries.aoserv.daemon.email.maillog;

/*
 * Copyright 2002-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.util.*;

/**
 * Represents one line of the maillog file.
 *
 * @author  AO Industries, Inc.
 */
abstract public class SmtpEntry extends MailLogEntry {

    public static final String PROTOCOL="sendmail";

    protected String messageID;

    protected SmtpEntry(Date date, String host, int pid, String messageID) {
        super(date, host, pid);
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpEntry.class, "<init>(Date,String,int,String)", null);
        try {
            this.messageID=messageID;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    final public String getMessageID() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpEntry.class, "getMessageID()", null);
        try {
            return messageID;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    final public String getProtocol() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpEntry.class, "getProtocol()", null);
        try {
            return PROTOCOL;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String toString() {
        Profiler.startProfile(Profiler.FAST, SmtpEntry.class, "toString()", null);
        try {
            return super.toString()+", messageID="+messageID;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}