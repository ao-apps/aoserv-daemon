/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2012, 2015, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.util;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.NullArgumentException;
import java.io.IOException;
import java.util.Objects;

/**
 * Manages symbolic links for Tomcat installations.
 *
 * @author  AO Industries, Inc.
 */
public class UpgradeSymlink {

  private final String oldLinkPath;
  private final String oldLinkTarget;
  private final String newLinkPath;
  private final String newLinkTarget;

  /**
   * @param oldLinkTarget  if {@code null} the link will be created if missing
   */
  public UpgradeSymlink(String oldLinkPath, String oldLinkTarget, String newLinkPath, String newLinkTarget) {
    NullArgumentException.checkNotNull(oldLinkPath, "oldLinkPath");
    if (oldLinkPath.equals(newLinkPath) && Objects.equals(oldLinkTarget, newLinkTarget)) {
      throw new IllegalArgumentException("oldLinkPath = newLinkPath and oldLinkTarget = newLinkTarget: linkPath = " + oldLinkPath + ", linkTarget = " + oldLinkTarget);
    }
    if ((newLinkPath == null) != (newLinkTarget == null)) {
      throw new IllegalArgumentException("(newLinkPath == null) != (newLinkTarget == null): (" + newLinkPath + " == null) != (" + newLinkTarget + " == null)");
    }
    if ((oldLinkTarget == null) && (newLinkTarget == null)) {
      throw new IllegalArgumentException("(oldLinkTarget == null) && (newLinkTarget == null): (" + oldLinkTarget + " == null) && (" + newLinkTarget + " == null)");
    }
    this.oldLinkPath = oldLinkPath;
    this.oldLinkTarget = oldLinkTarget;
    this.newLinkPath = newLinkPath;
    this.newLinkTarget = newLinkTarget;
  }

  /**
   * @param oldLinkTarget  if {@code null} the link will be created if missing
   */
  public UpgradeSymlink(String linkPath, String oldLinkTarget, String newLinkTarget) {
    this(linkPath, oldLinkTarget, newLinkTarget == null ? null : linkPath, newLinkTarget);
  }

  /**
   * Gets the relative path to the old symlink.
   */
  public String getOldLinkPath() {
    return oldLinkPath;
  }

  /**
   * Gets the old target.
   */
  public String getOldLinkTarget() {
    return oldLinkTarget;
  }

  /**
   * Gets the relative path to the new symlink or {@code null} if should not exist.
   */
  public String getNewLinkPath() {
    return newLinkPath;
  }

  /**
   * Gets the new link target or {@code null} if should not exist.
   */
  public String getNewLinkTarget() {
    return newLinkTarget;
  }

  /**
   * Updates the symlink if needed.  Will also reset the UID
   * and GID if they mismatch.
   *
   * @return  <code>true</code> if link modified.  UID and GID changes alone will not
   *          count as a change.
   */
  public boolean upgradeLinkTarget(PosixFile baseDirectory, int uid, int gid) throws IOException {
    boolean needsRestart = false;
    PosixFile oldLink = new PosixFile(baseDirectory.getPath() + "/" + oldLinkPath);
    Stat oldLinkStat = oldLink.getStat();
    PosixFile newLink;
    Stat newLinkStat;
    if (newLinkPath == null) {
      newLink = null;
      newLinkStat = null;
    } else if (newLinkPath.equals(oldLinkPath)) {
      newLink = oldLink;
      newLinkStat = oldLinkStat;
    } else {
      newLink = new PosixFile(baseDirectory.getPath() + "/" + newLinkPath);
      newLinkStat = newLink.getStat();
    }
    if (oldLinkTarget == null) {
      assert newLinkPath != null;
      assert newLinkTarget != null;
      assert newLink != null;
      assert newLinkStat != null;
      if (!oldLinkStat.exists() && !newLinkStat.exists()) {
        newLink.symLink(newLinkTarget);
        newLinkStat = newLink.getStat();
        needsRestart = true;
      }
    } else if (oldLinkStat.exists() && oldLinkStat.isSymLink()) {
      if (oldLink.readLink().equals(oldLinkTarget)) {
        oldLink.delete();
        if (newLinkTarget != null) {
          assert newLink != null;
          newLink.symLink(newLinkTarget);
          newLinkStat = newLink.getStat();
        }
        needsRestart = true;
      }
    }
    // Check ownership
    if (
        newLinkStat != null
            && newLinkStat.exists()
            && (newLinkStat.getUid() != uid || newLinkStat.getGid() != gid)
    ) {
      assert newLink != null;
      newLink.chown(uid, gid);
    }
    return needsRestart;
  }
}
