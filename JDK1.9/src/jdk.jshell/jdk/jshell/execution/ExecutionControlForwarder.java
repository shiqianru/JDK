/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package jdk.jshell.execution;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControl.ClassBytecodes;
import jdk.jshell.spi.ExecutionControl.ClassInstallException;
import jdk.jshell.spi.ExecutionControl.EngineTerminationException;
import jdk.jshell.spi.ExecutionControl.InternalException;
import jdk.jshell.spi.ExecutionControl.NotImplementedException;
import jdk.jshell.spi.ExecutionControl.ResolutionException;
import jdk.jshell.spi.ExecutionControl.StoppedException;
import jdk.jshell.spi.ExecutionControl.UserException;
import static jdk.jshell.execution.RemoteCodes.*;

/**
 * Forwards commands from the input to the specified {@link ExecutionControl}
 * instance, then responses back on the output.
 */
class ExecutionControlForwarder {

    /**
     * Maximum number of characters for writeUTF().  Byte maximum is 65535, at
     * maximum three bytes per character that is 65535 / 3 == 21845.  Minus one
     * for safety.
     */
    private static final int MAX_UTF_CHARS = 21844;

    private final ExecutionControl ec;
    private final ObjectInput in;
    private final ObjectOutput out;

    ExecutionControlForwarder(ExecutionControl ec, ObjectInput in, ObjectOutput out) {
        this.ec = ec;
        this.in = in;
        this.out = out;
    }

    private boolean writeSuccess() throws IOException {
        writeStatus(RESULT_SUCCESS);
        flush();
        return true;
    }

    private boolean writeSuccessAndResult(String result) throws IOException {
        writeStatus(RESULT_SUCCESS);
        writeUTF(result);
        flush();
        return true;
    }

    private boolean writeSuccessAndResult(Object result) throws IOException {
        writeStatus(RESULT_SUCCESS);
        writeObject(result);
        flush();
        return true;
    }

    private void writeStatus(int status) throws IOException {
        out.writeInt(status);
    }

    private void writeObject(Object o) throws IOException {
        out.writeObject(o);
    }

    private void writeInt(int i) throws IOException {
        out.writeInt(i);
    }

    private void writeUTF(String s) throws IOException {
        if (s == null) {
            s = "";
        } else if (s.length() > MAX_UTF_CHARS) {
            // Truncate extremely long strings to prevent writeUTF from crashing the VM
            s = s.substring(0, MAX_UTF_CHARS);
        }
        out.writeUTF(s);
    }

    private void flush() throws IOException {
        out.flush();
    }

    private boolean processCommand() throws IOException {
        try {
            int prefix = in.readInt();
            if (prefix != COMMAND_PREFIX) {
                throw new EngineTerminationException("Invalid command prefix: " + prefix);
            }
            String cmd = in.readUTF();
            switch (cmd) {
                case CMD_LOAD: {
                    // Load a generated class file over the wire
                    ClassBytecodes[] cbcs = (ClassBytecodes[]) in.readObject();
                    ec.load(cbcs);
                    return writeSuccess();
                }
                case CMD_REDEFINE: {
                    // Load a generated class file over the wire
                    ClassBytecodes[] cbcs = (ClassBytecodes[]) in.readObject();
                    ec.redefine(cbcs);
                    return writeSuccess();
                }
                case CMD_INVOKE: {
                    // Invoke executable entry point in loaded code
                    String className = in.readUTF();
                    String methodName = in.readUTF();
                    String res = ec.invoke(className, methodName);
                    return writeSuccessAndResult(res);
                }
                case CMD_VAR_VALUE: {
                    // Retrieve a variable value
                    String className = in.readUTF();
                    String varName = in.readUTF();
                    String res = ec.varValue(className, varName);
                    return writeSuccessAndResult(res);
                }
                case CMD_ADD_CLASSPATH: {
                    // Append to the claspath
                    String cp = in.readUTF();
                    ec.addToClasspath(cp);
                    return writeSuccess();
                }
                case CMD_STOP: {
                    // Stop the current execution
                    try {
                        ec.stop();
                    } catch (Throwable ex) {
                        // JShell-core not waiting for a result, ignore
                    }
                    return true;
                }
                case CMD_CLOSE: {
                    // Terminate this process
                    try {
                        ec.close();
                    } catch (Throwable ex) {
                        // JShell-core not waiting for a result, ignore
                    }
                    return true;
                }
                default: {
                    Object arg = in.readObject();
                    Object res = ec.extensionCommand(cmd, arg);
                    return writeSuccessAndResult(res);
                }
            }
        } catch (IOException ex) {
            // handled by the outer level
            throw ex;
        } catch (EngineTerminationException ex) {
            writeStatus(RESULT_TERMINATED);
            writeUTF(ex.getMessage());
            flush();
            return false;
        } catch (NotImplementedException ex) {
            writeStatus(RESULT_NOT_IMPLEMENTED);
            writeUTF(ex.getMessage());
            flush();
            return true;
        } catch (InternalException ex) {
            writeStatus(RESULT_INTERNAL_PROBLEM);
            writeUTF(ex.getMessage());
            flush();
            return true;
        } catch (ClassInstallException ex) {
            writeStatus(RESULT_CLASS_INSTALL_EXCEPTION);
            writeUTF(ex.getMessage());
            writeObject(ex.installed());
            flush();
            return true;
        } catch (UserException ex) {
            writeStatus(RESULT_USER_EXCEPTION);
            writeUTF(ex.getMessage());
            writeUTF(ex.causeExceptionClass());
            writeObject(ex.getStackTrace());
            flush();
            return true;
        } catch (ResolutionException ex) {
            writeStatus(RESULT_CORRALLED);
            writeInt(ex.id());
            writeObject(ex.getStackTrace());
            flush();
            return true;
        } catch (StoppedException ex) {
            writeStatus(RESULT_STOPPED);
            flush();
            return true;
        } catch (Throwable ex) {
            writeStatus(RESULT_TERMINATED);
            writeUTF(ex.getMessage());
            flush();
            return false;
        }
    }

    void commandLoop() {
        try {
            while (processCommand()) {
                // condition is loop action
            }
        } catch (IOException ex) {
            // drop out of loop
        }
    }

}
