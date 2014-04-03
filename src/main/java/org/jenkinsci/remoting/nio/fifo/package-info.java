/**
 * Buffer between one writer and reader.
 *
 * <p>
 * Logically, this data structure defines an InputStream/OutputStream pair, where data written
 * from one end appears from the other end in FIFO fashion.
 *
 * <p>
 * The implementation is based on a linked list of byte[]s (called {@link BufferPage}). Both
 * the writer end and the reader end maintains a pointer to a specific byte in a {@link BufferPage},
 * and read/write happens against this pointer. Buffer pages are singly-linked, so as the data
 * is consumed unneeded pages get garbage collected, and the memory usage is proportional to
 * the amount of actual data in memory, as opposed to a fixed preset amount.
 */
package org.jenkinsci.remoting.nio.fifo;
