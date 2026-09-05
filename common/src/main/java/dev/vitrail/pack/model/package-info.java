/**
 * The words a pack is read in: target names, formats and sizes, the shapes of its images and
 * buffers, the stages and names of its programs and the fallback tree between them.
 * <p>
 * Nothing here reads a file or a setting, and nothing here imports another package of the
 * engine: these are the value types every other package of the reading is written against, and
 * keeping them apart is what lets those packages be read in one direction. A type that starts
 * needing the source or the options does not belong here any more.
 */
package dev.vitrail.pack.model;
