package music.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Connection {

	private static final Logger log = LogManager.getLogger(Connection.class);
	
	private final Socket socket;
	
	// Lists for input and output streams that are used (besides the basic InputStream/OutputStream).
	// Storing them in a list and closing them in a loop ensures all streams have been closed.
	private final ArrayList<InputStream> inStreams = new ArrayList<InputStream>();
	private final ArrayList<OutputStream> outStreams = new ArrayList<OutputStream>();
	
	private volatile int offset = 0;
	
	public Connection(Socket socket) {
		this.socket = socket;
		
		try {
			/*
			 * Input streams block until their corresponding output streams have "written and flushed the header".
			 * Therefore output streams are setup first, and then their corresponding input streams.
			 */
			// Output streams setup
			log.debug("Setting up output streams...");
			OutputStream out = socket.getOutputStream();
			outStreams.add(new ObjectOutputStream(out));
			outStreams.add(new BufferedOutputStream(out));
			outStreams.add(new DataOutputStream(out));
			
			// Flushing output streams
			out.flush();
			for(OutputStream stream: outStreams) {
				stream.flush();
			}
			log.debug("Done.");
			
			// Input streams setup
			log.debug("Setting up input streams...");
			InputStream in = socket.getInputStream();
			inStreams.add(new ObjectInputStream(in));
			inStreams.add(new BufferedInputStream(in));
			inStreams.add(new DataInputStream(in));
			log.debug("Done.");
			
		} catch (IOException e) {
			log.error("Failed to set up streams with " + socket.getInetAddress(), e);
			disconnect();
			return;
		}
	}
	
	private InputStream getInput(Class<?> classType) {
		for(InputStream stream: inStreams) {
			if(stream.getClass() == classType) return stream;
		}
		log.error("Input stream " + classType + " could not be found");
		disconnect();
		return null;
	}
	
	private OutputStream getOutput(Class<?> classType) {
		for(OutputStream stream: outStreams) {
			if(stream.getClass() == classType) return stream;
		}
		log.error("Output stream " + classType + " could not be found");
		disconnect();
		return null;
	}
	
	private int readInt() {
		int value = -1;
		try {
			value = ((DataInputStream) getInput(DataInputStream.class)).readInt();
		} catch (EOFException e) {
			log.error("Read reached end of stream before finished reading from " + socket.getInetAddress(), e);
			disconnect();
		} catch(IOException e) {
			log.error("IO error when awaiting message from " + socket.getInetAddress(), e);
			disconnect();
		} catch(NullPointerException e) {
			log.error(e);
			disconnect();
		}
		return value;
	}
	
	public void writeInt(int value) {
		// Send integer
		final DataOutputStream out = (DataOutputStream) getOutput(DataOutputStream.class);
		try {
			out.flush();
			
			// Send a message in the form of an integer
			log.debug("Sending integer: " + value);
			out.writeInt(value);
			out.flush();
			log.debug("Done.");
		} catch (IOException e) {
			log.error("Error sending integer [" + value + "] to [" + socket.getInetAddress() + "].", e);
			disconnect();
		}
	}
	
	public Command readCommand() {
		// Read integer like normal
		int value = readInt();
		
		// If value read is not an acknowledgement
		if(value != Command.ACK.ordinal()) {
			// Send an acknowledgement that the value was read
			writeCommand(Command.ACK);
		}
		// Returns the command associated with that ordinal
		return Command.values()[value];
	}
	
	public void writeCommand(Command command) {
		int value = command.ordinal();
		writeInt(value);
		
		// If value sent is not an acknowledgement
		if(value != Command.ACK.ordinal()) {
			// Wait for acknowledgement
			readACK();
		}	
	}
	
	/**
	 * Used to consume the ACK expected to be sent, also verifies if 
	 * value sent was an ACK.
	 */
	public void readACK() {
		Command command = readCommand();
		if(!command.equals(Command.ACK)) {
			log.error("ACK is incorrect, got " + command);
			disconnect();
		}
	}
	
	public void readFile(String newPath) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				// For reading file size and file extension
				final DataInputStream dInput = (DataInputStream) getInput(DataInputStream.class);
				
				// Retrieving file size
				long fileSize = -1;
				try {
					fileSize = dInput.readLong();
				} catch(IOException e) {
					log.error("IO Error getting file size from " + socket.getInetAddress(), e);
					disconnect();
					return;
				}
				
				// Retrieving file extension
				String extension = "";
				try {
					extension = dInput.readUTF();
				} catch (IOException e) {
					log.error("IO Error getting file extension.", e);
					disconnect();
					return;
				}
				
				// Creating name for file to be downloaded, name is based on current date and time
				// The file will be renamed after processed by Storage.
				DateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss");
				Date date = new Date();
				String name = dateFormat.format(date) + extension;
				
				try(
					// For writing the incoming file to the hard drive
					FileOutputStream fOutput = new FileOutputStream(newPath + "/" + name);
					BufferedOutputStream bOutput = new BufferedOutputStream(fOutput)
				) {
					fOutput.flush();
					bOutput.flush();
								
					// For reading the incoming file
					BufferedInputStream bInput = (BufferedInputStream) getInput(BufferedInputStream.class);
					
					// Declaring buffer size
					int bufferSize = 1024 * 8;
					byte[] bytes = new byte[bufferSize];
					
					log.debug("Downloading " + (fileSize / 1000.0) + "kb file...");
					
					// Reading from the input stream and saving to a file	
					int bytesReceived = 0;
					for(int count; bytesReceived < fileSize && (count = bInput.read(bytes)) != -1;) {
						// bytes is the actual data to write,
						// 0 is the offset,
						// count is the number of bytes to write
						bOutput.write(bytes, 0, count);
						bytesReceived += count;
					}
					bOutput.flush();
					fOutput.flush();
					
					log.debug("Done.");
				} catch (EOFException e) {
					log.error("End of file error.", e);
					disconnect();
				} catch (IOException e) {
					log.error("IO error.", e);
					disconnect();
				}
				
				// Send acknowledgement
				writeCommand(Command.ACK);
			}
		}).start();
	}
	
	public void writeFile(String filePath, boolean streaming) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				// For sending file size and file extension
				DataOutputStream dOutput = (DataOutputStream) getOutput(DataOutputStream.class);
				File file = new File(filePath);
				long fileSize = file.length();
				
				// Making sure the file exists
				if(!file.exists()) {
					log.error("Cannot send file. File " + filePath + " does not exist.");
					disconnect();
					return;
				}
				
				// If client is not intending to stream the data, the file size and file extension
				// data will be sent. Otherwise skip these steps and setup a second thread to listen
				// for data while a streaming file.
				if(!streaming) {
					// Sending file size
					try {
						dOutput.writeLong(fileSize);
						dOutput.flush();
					} catch(IOException e) {
						log.error("IO error sending file size.", e);
						disconnect();
						return;
					}
					
					// Sending file extension
					try {
						String fileName = file.getName();
						// Parse file name to find out extension type
						int dotIndex = fileName.lastIndexOf('.');
						String extension = "";
						if(dotIndex != -1) extension = fileName.substring(dotIndex, fileName.length());
						dOutput.writeUTF(extension);
						dOutput.flush();
					} catch(IOException e) {
						log.error("IO Error when parsing & sending file extension.", e);
						disconnect();
						return;
					}
				} else {
					/* Since the current thread will block due to writing a file to the output stream 
					 * a second thread is created. This thread listens to the connection in order
					 * to be told what part of the file the client wishes to stream.
					 */
					Thread offsetUpdater = new Thread(new Runnable() {

						@Override
						public void run() {
							while(offset != -1) {
								// Read number for how many bytes into the file to stream
								offset = readInt();
							}
						}
					});
					offsetUpdater.start();
				}
				
				try (
						// For reading the file in RAM
						FileInputStream fInput = new FileInputStream(file);
						BufferedInputStream bInput = new BufferedInputStream(fInput)
					) {			
					// Declaring buffer size
					int bufferSize = 1024 * 8;
					byte[] bytes = new byte[bufferSize];
					
					// For writing the file to the output stream
					final BufferedOutputStream bOutput = (BufferedOutputStream) getOutput(BufferedOutputStream.class);
					bOutput.flush();
					
					if(streaming) log.debug("Streaming file...");
					else log.debug("Sending " + (fileSize / 1000.0) + "kb file...");
					
					// Reading the data with read() and sending it with write()
					// -1 from read() means the end of stream (no more bytes to read)
					// -1 from offset means the client wishes to terminate streaming
					for(int count; (count = bInput.read(bytes)) != -1 && offset != -1;) {
						// bytes is the actual data to write,
						// offset is the offset (byte index of where to start in the file)
						// count is the number of bytes to write
						bOutput.write(bytes, offset, count);
					}
					bOutput.flush();
					
					log.debug("Done.");
				} catch (FileNotFoundException e) {
					log.error("File not found error. ", e);
					disconnect();
				} catch (IOException e) {
					log.error("IO Error.", e);
					disconnect();
				} finally {
					offset = -1; // This will effectively close the offset updater thread.
				}
				
				// Wait for acknowledgement
				readACK();
			}
		}).start();
	}
	
	protected synchronized void playLocal(File file) {
		
		/*
		 * This is a bare-bones basic example for playing files locally,
		 * need to cover all specific types of exceptions instead of a
		 * general catch clause. Also will need to incorporate stop, play, skipping forward, etc.
		 */
		
		AudioInputStream ais = null;
		try {
			ais = AudioSystem.getAudioInputStream(new BufferedInputStream(new FileInputStream(file))); 
			try (Clip clip = AudioSystem.getClip()) {
				log.debug("Playing clip...");
	            clip.open(ais);
	            clip.start();
	            Thread.sleep(100); // given clip.drain a chance to start
	            clip.drain();
	            log.debug("Done.");
	        } catch(Exception e) {
				log.error("AudioInputStream created, but Clip failed to play");
			}
		} catch(Exception e) {
			log.error("AudioInputStream FAILED to instantiate:", e);
		}
    }
	
	public void stream() {
		try (
				// For streaming the incoming bytes
				BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
				AudioInputStream ais = AudioSystem.getAudioInputStream(bis);
			){
			
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, ais.getFormat());
			SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(ais.getFormat());
			line.flush();
			line.start();
			
			// Declaring buffer size
			byte[] buffer = new byte[3 * 1024 * ais.getFormat().getFrameSize()];
			
			log.debug("Streaming bytes...");
			
			// Streaming bytes
			for(int count; (count = ais.read(buffer)) != -1;) {
				// buffer is the array containing data to be written
				// 0 is the offset,
				// count is the number of bytes to write
				line.write(buffer, 0, count);
			}
			
			// Close resources
			line.drain();
			line.stop();
			line.close();
			ais.close();
		} catch(Exception e) {
			log.error("ERROR in playRemote()", e);
		}
		
		// Send acknowledgement
		writeCommand(Command.ACK);
	}
	
	public Object readObject() {
		final ObjectInputStream objIn = (ObjectInputStream) getInput(ObjectInputStream.class);
		Object object = null;
		try {
			object = objIn.readObject();
		} catch(ClassNotFoundException e) {
			log.error("Could not find Class.", e);
		} catch (IOException e) {
			log.error("IO Exception.", e);
		}
		
		// Send acknowledgement that object was read
		writeCommand(Command.ACK);
		return object;
	}
	
	public void writeObject(Object object) {		
		final ObjectOutputStream objOut = (ObjectOutputStream) getOutput(ObjectOutputStream.class);
		try {
			objOut.flush();
			
			objOut.writeObject(object);
			objOut.flush();
			log.debug("Done.");
		} catch (IOException e) {
			log.error("IO Exception.", e);
		}
		
		// Wait for acknowledgement
		readACK();
	}
	
	public void disconnect() {
		// Disconnect only if the connection has not been closed
		if(socket.isClosed()) return;
		
		log.debug("*** Disconnecting from " + socket.getInetAddress() + " ***");
		
		log.debug("Closing input streams...");
		for(InputStream stream: inStreams) {
			try {
				stream.close();
			} catch (IOException e) {
				log.error("Error closing input stream with [" + socket.getInetAddress() + "].", e);
			}
		}
		log.debug("Done.");
		
		log.debug("Closing output streams...");
		for(OutputStream stream: outStreams) {
			try {
				stream.close();
			} catch (IOException e) {
				log.error("Error closing output stream with [" + socket.getInetAddress() + "].", e);
			}
		}
		log.debug("Done.");
		
		try {
			// Close the socket and its associated input/output streams
			socket.close();
		}
		catch(IOException e) {
			log.error("Error closing socket with [" + socket.getInetAddress() + "].", e);
		}
		log.debug("*** Done with " + socket.getInetAddress() + " ***");
	}
	
	public String getHostAddress() {
		return socket.getInetAddress().getHostAddress();
	}
}