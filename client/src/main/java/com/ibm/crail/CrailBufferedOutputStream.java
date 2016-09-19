/*
 * Crail: A Multi-tiered Distributed Direct Access File System
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import sun.nio.ch.DirectBuffer;

import org.slf4j.Logger;

import com.ibm.crail.conf.CrailConstants;
import com.ibm.crail.utils.CrailUtils;


public class CrailBufferedOutputStream extends OutputStream implements CrailOutputStream {
	public static final Logger LOG = CrailUtils.getLogger();
	
	private CrailOutputStream outputStream;
	private ByteBuffer internalBuf;
	private byte[] tmpBuf; 
	private CrailFS crailFS;
	
	public CrailBufferedOutputStream(CrailFS crailFS, CrailOutputStream outputStream) throws IOException {
		this.crailFS = crailFS;
		this.outputStream = outputStream;
		this.internalBuf = crailFS.allocateBuffer();
		this.internalBuf.clear();		
		this.tmpBuf = new byte[1];
	}

	public final synchronized void write(int b) throws IOException {
		tmpBuf[0] = (byte) b;
		this.write(tmpBuf);
	}
	
	public final synchronized void write(byte[] buf) throws IOException {
		this.write(buf, 0, buf.length);
	}

	public final synchronized void write(byte[] buf, int off, int len) throws IOException {
		try {
			if (buf == null) {
				throw new NullPointerException();
			} else if ((off < 0) || (off > buf.length) || (len < 0) || ((off + len) > buf.length) || ((off + len) < 0)) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				// LOG.info("len = 0, nothing to write");
				return;
			}

			while (len > 0) {
				int bufferRemaining = Math.min(len, internalBuf.remaining());
				internalBuf.put(buf, off, bufferRemaining);
				off += bufferRemaining;
				len -= bufferRemaining;
				if (internalBuf.remaining() == 0) {
					internalBuf.flip();
					write(internalBuf);
					internalBuf.clear();
				}
			}
		} catch (Exception e) {
			throw new IOException(e);
		}
	}	
	
	public final synchronized void write(ByteBuffer dataBuf) throws IOException {
		try {
			if (dataBuf instanceof DirectBuffer) {
				Future<CrailResult> future = writeAsync(dataBuf);
				future.get(CrailConstants.DATA_TIMEOUT, TimeUnit.MILLISECONDS);
			} else {
				int len = dataBuf.remaining();
				while (len > 0) {
					int bufferRemaining = Math.min(len, internalBuf.remaining());
					int oldLimit = dataBuf.limit();
					dataBuf.limit(dataBuf.position() + bufferRemaining);
					internalBuf.put(dataBuf);
					dataBuf.limit(oldLimit);
					len -= bufferRemaining;
					
					if (internalBuf.remaining() == 0) {
						internalBuf.flip();
						Future<CrailResult> future = writeAsync(internalBuf);
						future.get(CrailConstants.DATA_TIMEOUT, TimeUnit.MILLISECONDS);
						internalBuf.clear();
					}
				}
			}
		} catch (Exception e) {
			throw new IOException(e);
		}
	}
	
	public final synchronized Future<CrailResult> writeAsync(ByteBuffer dataBuf) throws IOException {
		try {
			Future<CrailResult> future = outputStream.writeAsync(dataBuf);
			return future;			
		} catch(Exception e){
			throw new IOException(e);
		}
	}
	
	public synchronized Future<?> purge() throws IOException {
		internalBuf.flip();
		Future<CrailResult> future = writeAsync(internalBuf);
		internalBuf.clear();
		return future;
	}	
	
	public synchronized Future<Void> sync() throws IOException {
		Future<Void> future = outputStream.sync();
		return future;
	}

	public synchronized void close() throws IOException {
		try {
			if (!outputStream.isOpen()){
				return;
			}
			
			if (internalBuf.position() > 0) {
				internalBuf.flip();
				write(internalBuf);
				internalBuf.clear();
			} 
			outputStream.close();
			crailFS.freeBuffer(internalBuf);
		} catch (Exception e) {
			throw new IOException(e);
		} 
	}
	
	public synchronized long getPos() throws IOException {
		return outputStream.position();
	}

	public int getRemaining(){
		return internalBuf.remaining();
	}

	@Override
	public boolean isOpen() {
		return outputStream.isOpen();
	}

	@Override
	public long position() {
		return outputStream.position();
	}

	@Override
	public long getWriteHint() {
		return outputStream.getWriteHint();
	}
}