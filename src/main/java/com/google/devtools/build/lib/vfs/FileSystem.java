// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.vfs;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.vfs.Dirent.Type;
import com.google.devtools.build.lib.vfs.Path.PathFactory;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.Collection;
import java.util.List;

/**
 * This interface models a file system using UNIX the naming scheme.
 */
@ThreadSafe
public abstract class FileSystem {

  /** Type of hash function to use for digesting files. */
  public enum HashFunction {
    MD5(16),
    SHA1(20);

    private final int digestSize;

    HashFunction(int digestSize) {
      this.digestSize = digestSize;
    }

    /** Converts to {@link HashFunction}. */
    public static class Converter extends EnumConverter<HashFunction> {
      public Converter() {
        super(HashFunction.class, "hash function");
      }
    }

    public boolean isValidDigest(byte[] digest) {
      return digest != null && digest.length == digestSize;
    }
  }

  // This is effectively final, should be changed only in unit-tests!
  private static HashFunction DIGEST_FUNCTION;
  static {
    try {
      DIGEST_FUNCTION = new HashFunction.Converter().convert(
          System.getProperty("bazel.DigestFunction", "MD5"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException(e);
    }
  }

  @VisibleForTesting
  public static void setDigestFunctionForTesting(HashFunction value) {
    DIGEST_FUNCTION = value;
  }

  public static HashFunction getDigestFunction() {
    return DIGEST_FUNCTION;
  }

  private enum UnixPathFactory implements PathFactory {
    INSTANCE {
      @Override
      public Path createRootPath(FileSystem filesystem) {
        return new Path(filesystem, PathFragment.ROOT_DIR, null);
      }

      @Override
      public Path createChildPath(Path parent, String childName) {
        return new Path(parent.getFileSystem(), childName, parent);
      }

      @Override
      public Path getCachedChildPathInternal(Path path, String childName) {
        return Path.getCachedChildPathInternal(path, childName, /*cacheable=*/ true);
      }
    };
  }

  /**
   * An exception thrown when attempting to resolve an ordinary file as a symlink.
   */
  protected static final class NotASymlinkException extends IOException {
    public NotASymlinkException(Path path) {
      super(path.toString());
    }
  }

  protected final Path rootPath;

  protected FileSystem() {
    this.rootPath = getPathFactory().createRootPath(this);
  }

  /** Returns filesystem-specific path factory. */
  protected PathFactory getPathFactory() {
    return UnixPathFactory.INSTANCE;
  }

  /**
   * Returns an absolute path instance, given an absolute path name, without
   * double slashes, .., or . segments. While this method will normalize the
   * path representation by creating a structured/parsed representation, it will
   * not cause any IO. (e.g., it will not resolve symbolic links if it's a Unix
   * file system.
   */
  public Path getPath(String pathName) {
    return getPath(PathFragment.create(pathName));
  }

  /**
   * Returns an absolute path instance, given an absolute path name, without
   * double slashes, .., or . segments. While this method will normalize the
   * path representation by creating a structured/parsed representation, it will
   * not cause any IO. (e.g., it will not resolve symbolic links if it's a Unix
   * file system.
   */
  public Path getPath(PathFragment pathName) {
    if (!pathName.isAbsolute()) {
      throw new IllegalArgumentException(pathName.getPathString()  + " (not an absolute path)");
    }
    return rootPath.getRelative(pathName);
  }

  /**
   * Returns a path representing the root directory of the current file system.
   */
  public final Path getRootDirectory() {
    return rootPath;
  }

  /**
   * Returns whether or not the FileSystem supports modifications of files and
   * file entries.
   *
   * <p>Returns true if FileSystem supports the following:
   * <ul>
   * <li>{@link #setWritable(Path, boolean)}</li>
   * <li>{@link #setExecutable(Path, boolean)}</li>
   * </ul>
   *
   * The above calls will result in an {@link UnsupportedOperationException} on
   * a FileSystem where this method returns {@code false}.
   */
  public abstract boolean supportsModifications();

  /**
   * Returns whether or not the FileSystem supports symbolic links.
   *
   * <p>Returns true if FileSystem supports the following:
   * <ul>
   * <li>{@link #createSymbolicLink(Path, PathFragment)}</li>
   * <li>{@link #getFileSize(Path, boolean)} where {@code followSymlinks=false}</li>
   * <li>{@link #getLastModifiedTime(Path, boolean)} where {@code followSymlinks=false}</li>
   * <li>{@link #readSymbolicLink(Path)} where the link points to a non-existent file</li>
   * </ul>
   *
   * The above calls may result in an {@link UnsupportedOperationException} on
   * a FileSystem where this method returns {@code false}. The implementation can try to emulate
   * these calls at its own discretion.
   */
  public abstract boolean supportsSymbolicLinksNatively();

  /**
   * Returns whether or not the FileSystem supports hard links.
   *
   * <p>Returns true if FileSystem supports the following:
   *
   * <ul>
   * <li>{@link #createFSDependentHardLink(Path, Path)}
   * </ul>
   *
   * The above calls may result in an {@link UnsupportedOperationException} on a FileSystem where
   * this method returns {@code false}. The implementation can try to emulate these calls at its own
   * discretion.
   */
  protected abstract boolean supportsHardLinksNatively();

  /***
   * Returns true if file path is case-sensitive on this file system. Default is true.
   */
  public abstract boolean isFilePathCaseSensitive();

  /**
   * Returns the type of the file system path belongs to.
   *
   * <p>The string returned is obtained directly from the operating system, so
   * it's a best guess in absence of a guaranteed api.
   *
   * <p>This implementation uses <code>/proc/mounts</code> to determine the
   * file system type.
   */
  public String getFileSystemType(Path path) {
    String fileSystem = "unknown";
    int bestMountPointSegmentCount = -1;
    try {
      Path canonicalPath = path.resolveSymbolicLinks();
      Path mountTable = path.getRelative("/proc/mounts");
      try (InputStreamReader reader = new InputStreamReader(mountTable.getInputStream(),
          ISO_8859_1)) {
        for (String line : CharStreams.readLines(reader)) {
          String[] words = line.split("\\s+");
          if (words.length >= 3) {
            if (!words[1].startsWith("/")) {
              continue;
            }
            Path mountPoint = path.getFileSystem().getPath(words[1]);
            int segmentCount = mountPoint.asFragment().segmentCount();
            if (canonicalPath.startsWith(mountPoint) && segmentCount > bestMountPointSegmentCount) {
              bestMountPointSegmentCount = segmentCount;
              fileSystem = words[2];
            }
          }
        }
      }
    } catch (IOException e) {
      // pass
    }
    return fileSystem;
  }


  /**
   * Creates a directory with the name of the current path. See
   * {@link Path#createDirectory} for specification.
   */
  protected abstract boolean createDirectory(Path path) throws IOException;

  /**
   * Returns the size in bytes of the file denoted by {@code path}. See
   * {@link Path#getFileSize(Symlinks)} for specification.
   *
   * <p>Note: for <@link FileSystem>s where {@link #supportsSymbolicLinksNatively()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException} if {@code followSymLinks=false}.
   */
  protected abstract long getFileSize(Path path, boolean followSymlinks) throws IOException;

  /**
   * Deletes the file denoted by {@code path}. See {@link Path#delete} for
   * specification.
   */
  protected abstract boolean delete(Path path) throws IOException;

  /**
   * Returns the last modification time of the file denoted by {@code path}.
   * See {@link Path#getLastModifiedTime(Symlinks)} for specification.
   *
   * Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively()} returns
   * false, this method will throw an {@link UnsupportedOperationException} if
   * {@code followSymLinks=false}.
   */
  protected abstract long getLastModifiedTime(Path path,
                                              boolean followSymlinks)
      throws IOException;

  /**
   * Sets the last modification time of the file denoted by {@code path}. See
   * {@link Path#setLastModifiedTime} for specification.
   */
  protected abstract void setLastModifiedTime(Path path, long newTime) throws IOException;

  /**
   * Returns value of the given extended attribute name or null if attribute
   * does not exist or file system does not support extended attributes. Follows symlinks.
   * <p>Default implementation assumes that file system does not support
   * extended attributes and always returns null. Specific file system
   * implementations should override this method if they do provide support
   * for extended attributes.
   *
   * @param path the file whose extended attribute is to be returned.
   * @param name the name of the extended attribute key.
   * @return the value of the extended attribute associated with 'path', if
   *   any, or null if no such attribute is defined (ENODATA) or file
   *   system does not support extended attributes at all.
   * @throws IOException if the call failed for any other reason.
   */
  protected byte[] getxattr(Path path, String name) throws IOException {
    return null;
  }

  /**
   * Gets a fast digest for the given path and hash function type, or {@code null} if there
   * isn't one available or the filesystem doesn't support them. This digest should be
   * suitable for detecting changes to the file.
   */
  protected byte[] getFastDigest(Path path, HashFunction hashFunction) throws IOException {
    return null;
  }

  /**
   * Gets a fast digest for the given path, or {@code null} if there isn't one available or the
   * filesystem doesn't support them. This digest should be suitable for detecting changes to the
   * file.
   */
  protected final byte[] getFastDigest(Path path) throws IOException {
    return getFastDigest(path, DIGEST_FUNCTION);
  }

  /**
   * Returns whether the given digest is a valid digest for the default digest function.
   */
  public boolean isValidDigest(byte[] digest) {
    return DIGEST_FUNCTION.isValidDigest(digest);
  }

  /**
   * Returns the digest of the file denoted by the path, following
   * symbolic links, for the given hash digest function.
   *
   * @return a new byte array containing the file's digest
   * @throws IOException if the digest could not be computed for any reason
   */
  protected final byte[] getDigest(final Path path, HashFunction hashFunction) throws IOException {
    switch(hashFunction) {
      case MD5:
        return getMD5Digest(path);
      case SHA1:
        return getSHA1Digest(path);
      default:
        throw new IOException("Unsupported hash function: " + hashFunction);
    }
  }

  /**
   * Returns the digest of the file denoted by the path, following symbolic links.
   *
   * @return a new byte array containing the file's digest
   * @throws IOException if the digest could not be computed for any reason
   */
  protected byte[] getDigest(final Path path) throws IOException {
    return getDigest(path, DIGEST_FUNCTION);
  }

  /**
   * Returns the MD5 digest of the file denoted by {@code path}. See
   * {@link Path#getMD5Digest} for specification.
   */
  protected byte[] getMD5Digest(final Path path) throws IOException {
    // Naive I/O implementation.  Subclasses may (and do) optimize.
    // This code is only used by the InMemory or Zip or other weird FSs.
    return new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        return getInputStream(path);
      }
    }.hash(Hashing.md5()).asBytes();
  }

  /**
   * Returns the MD5 digest of the file denoted by {@code path}. See
   * {@link Path#getMD5Digest} for specification.
   */
  protected byte[] getSHA1Digest(final Path path) throws IOException {
    // Naive I/O implementation.  TODO(olaola): optimize!
    return new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        return getInputStream(path);
      }
    }.hash(Hashing.sha1()).asBytes();
  }

  /**
   * Returns true if "path" denotes an existing symbolic link. See
   * {@link Path#isSymbolicLink} for specification.
   */
  protected abstract boolean isSymbolicLink(Path path);

  /**
   * Appends a single regular path segment 'child' to 'dir', recursively
   * resolving symbolic links in 'child'. 'dir' must be canonical. 'maxLinks' is
   * the maximum number of symbolic links that may be traversed before it gives
   * up (the Linux kernel uses 32).
   *
   * <p>(This method does not need to be synchronized; but the result may be
   * stale in the case of concurrent modification.)
   *
   * @throws IOException if 'dir' is not an existing directory; or if
   *         stat(child) fails for any reason, or if 'child' is a symlink and
   *         readlink(child) fails for any reason (e.g. ENOENT, EACCES), or if
   *         the chain of symbolic links exceeds 'maxLinks'.
   */
  protected final Path appendSegment(Path dir, String child, int maxLinks) throws IOException {
    Path naive = dir.getChild(child);

    PathFragment linkTarget = resolveOneLink(naive);
    if (linkTarget == null) {
      return naive; // regular file or directory
    }

    if (maxLinks-- == 0) {
      throw new IOException(naive + " (Too many levels of symbolic links)");
    }
    if (linkTarget.isAbsolute()) { dir = rootPath; }
    for (String name : linkTarget.segments()) {
      if (name.equals(".") || name.isEmpty()) {
        // no-op
      } else if (name.equals("..")) {
        Path parent = dir.getParentDirectory();
        // root's parent is root, when canonicalizing, so this is a no-op.
        if (parent != null) { dir = parent; }
      } else {
        dir = appendSegment(dir, name, maxLinks);
      }
    }
    return dir;
  }

  /**
   * Helper method of {@link #resolveSymbolicLinks(Path)}. This method
   * encapsulates the I/O component of a full canonicalization operation.
   * Subclasses can (and do) provide more efficient implementations.
   *
   * <p>(This method does not need to be synchronized; but the result may be
   * stale in the case of concurrent modification.)
   *
   * @param path a path, of which all but the last segment is guaranteed to be
   *        canonical
   * @return {@link #readSymbolicLink} iff path is a symlink or null iff
   *         path exists but is not a symlink
   * @throws IOException if the file did not exist, or a parent directory could
   *         not be searched
   */
  protected PathFragment resolveOneLink(Path path) throws IOException {
    try {
      return readSymbolicLink(path);
    } catch (NotASymlinkException e) {
      // Not a symbolic link.  Check it exists.

      // (A simple call to lstat would replace all of this.)
      if (!exists(path, false)) {
        throw new FileNotFoundException(path + " (No such file or directory)");
      }

      // TODO(bazel-team): (2009) ideally, throw ENOTDIR if dir is not a dir, but that
      // would require twice as many stats, or a much more convoluted
      // implementation (like glibc's canonicalize.c).

      return null; //  exists.
    }
  }

  /**
   * Returns the canonical path for the given path. See
   * {@link Path#resolveSymbolicLinks} for specification.
   */
  protected Path resolveSymbolicLinks(Path path)
      throws IOException {
    Path parentNode = path.getParentDirectory();
    return parentNode == null
        ? path // (root)
        : appendSegment(resolveSymbolicLinks(parentNode), path.getBaseName(), 32);
  }

  /**
   * Returns the status of a file. See {@link Path#stat(Symlinks)} for
   * specification.
   *
   * <p>The default implementation of this method is a "lazy" one, based on
   * other accessor methods such as {@link #isFile}, etc. Subclasses may provide
   * more efficient specializations. However, we still try to follow Unix-like
   * semantics of failing fast in case of non-existent files (or in case of
   * permission issues).
   */
  protected FileStatus stat(final Path path, final boolean followSymlinks) throws IOException {
    FileStatus status = new FileStatus() {
      volatile Boolean isFile;
      volatile Boolean isDirectory;
      volatile Boolean isSymbolicLink;
      volatile Boolean isSpecial;
      volatile long size = -1;
      volatile long mtime = -1;

      @Override
      public boolean isFile() {
        if (isFile == null) { isFile = FileSystem.this.isFile(path, followSymlinks); }
        return isFile;
      }

      @Override
      public boolean isDirectory() {
        if (isDirectory == null) {
          isDirectory = FileSystem.this.isDirectory(path, followSymlinks);
        }
        return isDirectory;
      }

      @Override
      public boolean isSymbolicLink() {
        if (isSymbolicLink == null)  { isSymbolicLink = FileSystem.this.isSymbolicLink(path); }
        return isSymbolicLink;
      }

      @Override
      public boolean isSpecialFile() {
        if (isSpecial == null)  { isSpecial = FileSystem.this.isSpecialFile(path, followSymlinks); }
        return isSpecial;
      }

      @Override
      public long getSize() throws IOException {
        if (size == -1) { size = getFileSize(path, followSymlinks); }
        return size;
      }

      @Override
      public long getLastModifiedTime() throws IOException {
        if (mtime == -1) { mtime = FileSystem.this.getLastModifiedTime(path, followSymlinks); }
        return mtime;
      }

      @Override
      public long getLastChangeTime() {
        throw new UnsupportedOperationException();
      }

      @Override
      public long getNodeId() {
        throw new UnsupportedOperationException();
      }
    };

    // Fail fast in case if some operations will actually fail, since stat() call sometimes used
    // to verify file existence as well. We will use getLastModifiedTime() method for that purpose.
    status.getLastModifiedTime();

    return status;
  }

  /**
   * Like stat(), but returns null on failures instead of throwing.
   */
  protected FileStatus statNullable(Path path, boolean followSymlinks) {
    try {
      return stat(path, followSymlinks);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Like {@link #stat}, but returns null if the file is not found (corresponding to
   * {@code ENOENT} or {@code ENOTDIR} in Unix's stat(2) function) instead of throwing. Note that
   * this implementation does <i>not</i> successfully catch {@code ENOTDIR} exceptions. If the
   * instantiated filesystem can catch such errors, it should override this method to do so.
   */
  protected FileStatus statIfFound(Path path, boolean followSymlinks) throws IOException {
    try {
      return stat(path, followSymlinks);
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  /**
   * Returns true iff {@code path} denotes an existing directory. See
   * {@link Path#isDirectory(Symlinks)} for specification.
   */
  protected abstract boolean isDirectory(Path path, boolean followSymlinks);

  /**
   * Returns true iff {@code path} denotes an existing regular or special file.
   * See {@link Path#isFile(Symlinks)} for specification.
   */
  protected abstract boolean isFile(Path path, boolean followSymlinks);

  /**
   * Returns true iff {@code path} denotes a special file.
   * See {@link Path#isSpecialFile(Symlinks)} for specification.
   */
  protected abstract boolean isSpecialFile(Path path, boolean followSymlinks);

  /**
   * Creates a symbolic link. See {@link Path#createSymbolicLink(Path)} for
   * specification.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException}
   */
  protected abstract void createSymbolicLink(Path linkPath, PathFragment targetFragment)
      throws IOException;

  /**
   * Returns the target of a symbolic link. See {@link Path#readSymbolicLink}
   * for specification.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException} if the link points to a non-existent
   * file.
   *
   * @throws NotASymlinkException if the current path is not a symbolic link
   * @throws IOException if the contents of the link could not be read for any reason.
   */
  protected abstract PathFragment readSymbolicLink(Path path) throws IOException;

  /**
   * Returns the target of a symbolic link, under the assumption that the given path is indeed a
   * symbolic link (this assumption permits efficient implementations). See
   * {@link Path#readSymbolicLinkUnchecked} for specification.
   *
   * @throws IOException if the contents of the link could not be read for any reason.
   */
  protected PathFragment readSymbolicLinkUnchecked(Path path) throws IOException {
    return readSymbolicLink(path);
  }

  /**
   * Returns true iff {@code path} denotes an existing file of any kind. See
   * {@link Path#exists(Symlinks)} for specification.
   */
  protected abstract boolean exists(Path path, boolean followSymlinks);

  /**
   * Returns a collection containing the names of all entities within the
   * directory denoted by the {@code path}.
   *
   * @throws IOException if there was an error reading the directory entries
   */
  protected abstract Collection<Path> getDirectoryEntries(Path path) throws IOException;

  protected static Dirent.Type direntFromStat(FileStatus stat) {
    if (stat == null) {
      return Type.UNKNOWN;
    } else if (stat.isSpecialFile()) {
        return Type.UNKNOWN;
    } else if (stat.isFile()) {
      return Type.FILE;
    } else if (stat.isDirectory()) {
      return Type.DIRECTORY;
    } else if (stat.isSymbolicLink()) {
      return Type.SYMLINK;
    } else {
      return Type.UNKNOWN;
    }
  }

  /**
   * Returns a Dirents structure, listing the names of all entries within the
   * directory {@code path}, plus their types (file, directory, other).
   *
   * @param followSymlinks whether to follow symlinks when determining the file types of
   *     individual directory entries. No matter the value of this parameter, symlinks are
   *     followed when resolving the directory whose entries are to be read.
   * @throws IOException if there was an error reading the directory entries
   */
  protected Collection<Dirent> readdir(Path path, boolean followSymlinks) throws IOException {
    Collection<Path> children = getDirectoryEntries(path);
    List<Dirent> dirents = Lists.newArrayListWithCapacity(children.size());
    for (Path child : children) {
      Dirent.Type type = direntFromStat(statNullable(child, followSymlinks));
      dirents.add(new Dirent(child.getBaseName(), type));
    }
    return dirents;
  }

  /**
   * Returns true iff the file represented by {@code path} is readable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  protected abstract boolean isReadable(Path path) throws IOException;

  /**
   * Sets the file to readable (if the argument is true) or non-readable (if the
   * argument is false)
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications()}
   * returns false or which do not support unreadable files, this method will
   * throw an {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  protected abstract void setReadable(Path path, boolean readable)
    throws IOException;

  /**
   * Returns true iff the file represented by {@code path} is writable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  protected abstract boolean isWritable(Path path) throws IOException;

  /**
   * Sets the file to writable (if the argument is true) or non-writable (if the
   * argument is false)
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  protected abstract void setWritable(Path path, boolean writable)
      throws IOException;

  /**
   * Returns true iff the file represented by the path is executable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  protected abstract boolean isExecutable(Path path) throws IOException;

  /**
   * Sets the file to executable, if the argument is true. It is currently not
   * supported to unset the executable status of a file, so {code
   * executable=false} yields an {@link UnsupportedOperationException}.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  protected abstract void setExecutable(Path path, boolean executable) throws IOException;

  /**
   * Sets the file permissions. If permission changes on this {@link FileSystem}
   * are slow (e.g. one syscall per change), this method should aim to be faster
   * than setting each permission individually. If this {@link FileSystem} does
   * not support group or others permissions, those bits will be ignored.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications()}
   * returns false, this method will throw an
   * {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  protected void chmod(Path path, int mode) throws IOException {
    setReadable(path, (mode & 0400) != 0);
    setWritable(path, (mode & 0200) != 0);
    setExecutable(path, (mode & 0100) != 0);
  }

  /**
   * Creates an InputStream accessing the file denoted by the path.
   *
   * @throws IOException if there was an error opening the file for reading
   */
  protected abstract InputStream getInputStream(Path path) throws IOException;

  /**
   * Creates an OutputStream accessing the file denoted by path.
   *
   * @throws IOException if there was an error opening the file for writing
   */
  protected final OutputStream getOutputStream(Path path) throws IOException {
    return getOutputStream(path, false);
  }

  /**
   * Creates an OutputStream accessing the file denoted by path.
   *
   * @param append whether to open the output stream in append mode
   * @throws IOException if there was an error opening the file for writing
   */
  protected abstract OutputStream getOutputStream(Path path, boolean append) throws IOException;

  /**
   * Renames the file denoted by "sourceNode" to the location "targetNode".
   * See {@link Path#renameTo} for specification.
   */
  protected abstract void renameTo(Path sourcePath, Path targetPath) throws IOException;


  /**
   * Create a new hard link file at "linkPath" for file at "originalPath".
   *
   * @param linkPath The path of the new link file to be created
   * @param originalPath The path of the original file
   * @throws IOException if the original file does not exist or the link file already exists
   */
  protected void createHardLink(Path linkPath, Path originalPath) throws IOException {

    if (!originalPath.exists()) {
      throw new FileNotFoundException(
          "File \""
              + originalPath.getBaseName()
              + "\" linked from \""
              + linkPath.getBaseName()
              + "\" does not exist");
    }

    if (linkPath.exists()) {
      throw new FileAlreadyExistsException(
          "New link file \"" + linkPath.getBaseName() + "\" already exists");
    }

    createFSDependentHardLink(linkPath, originalPath);
  }

  /**
   * Create a new hard link file at "linkPath" for file at "originalPath".
   *
   * @param linkPath The path of the new link file to be created
   * @param originalPath The path of the original file
   * @throws IOException if there was an I/O error
   */
  protected abstract void createFSDependentHardLink(Path linkPath, Path originalPath)
      throws IOException;

  /**
   * Prefetch all directories and symlinks within the package
   * rooted at "path".  Enter at most "maxDirs" total directories.
   * Specializations for high-latency remote filesystems may wish to
   * implement this in order to warm the filesystem's internal caches.
   */
  protected void prefetchPackageAsync(Path path, int maxDirs) { }
}
