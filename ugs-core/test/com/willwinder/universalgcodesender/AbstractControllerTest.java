/*
    Copyright 2015-2020 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.willwinder.universalgcodesender;

import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.gcode.GcodeCommandCreator;
import com.willwinder.universalgcodesender.listeners.ControllerListener;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.services.MessageService;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.GcodeStreamTest;
import com.willwinder.universalgcodesender.utils.IGcodeStreamReader;
import com.willwinder.universalgcodesender.utils.Settings;
import com.willwinder.universalgcodesender.utils.SimpleGcodeStreamReader;
import org.apache.commons.io.FileUtils;
import org.easymock.EasyMock;
import org.easymock.IMockBuilder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 *
 * @author wwinder
 */
public class AbstractControllerTest {
    
    private final static AbstractCommunicator mockCommunicator = EasyMock.createMock(AbstractCommunicator.class);
    private final static ControllerListener mockListener = EasyMock.createMock(ControllerListener.class);
    private final static MessageService mockMessageService = EasyMock.createMock(MessageService.class);
    private final static GcodeCommandCreator gcodeCreator = new GcodeCommandCreator();

    private final Settings settings = new Settings();

    private static AbstractController instance;

    private static File tempDir = null;

    //@BeforeClass
    public static void init() throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        IMockBuilder<AbstractController> instanceBuilder = EasyMock
                .createMockBuilder(AbstractController.class)
                .addMockedMethods(
                        "closeCommBeforeEvent",
                        "closeCommAfterEvent",
                        "openCommAfterEvent",
                        "cancelSendBeforeEvent",
                        "cancelSendAfterEvent",
                        "pauseStreamingEvent",
                        "resumeStreamingEvent",
                        "isReadyToStreamCommandsEvent",
                        "isReadyToSendCommandsEvent",
                        "rawResponseHandler",
                        "isCommOpen")
                .withConstructor(ICommunicator.class)
                .withArgs(mockCommunicator);

        instance = instanceBuilder.createMock();
        AbstractController niceInstance = instanceBuilder.createNiceMock();

        // Initialize private variable.
        Field f = AbstractController.class.getDeclaredField("commandCreator");
        f.setAccessible(true);
        f.set(instance, gcodeCreator);
        f.set(niceInstance, gcodeCreator);
        
        instance.addListener(mockListener);
        instance.setMessageService(mockMessageService);
    }

    @BeforeClass
    static public void setup() throws IOException {
        tempDir = GcodeStreamTest.createTempDirectory();
    }

    @AfterClass
    static public void teardown() throws IOException {
        FileUtils.forceDelete(tempDir);
    }

    @Before
    public void setUp() throws Exception {
        // AbstractCommunicator calls a function on mockCommunicator that I
        // don't want to deal with.
        reset(mockCommunicator, mockListener, mockMessageService);
        init();
        reset(mockCommunicator, mockListener, mockMessageService);
    }


    ///////////////
    // UTILITIES //
    ///////////////
    private void openInstanceExpectUtility(String port, int portRate, boolean handleStateChange) throws Exception {
        instance.openCommAfterEvent();
        expect(expectLastCall()).anyTimes();
        mockMessageService.dispatchMessage(anyObject(), anyString());
        expect(expectLastCall()).anyTimes();
        instance.setControllerState(eq(ControllerState.CONNECTING));
        expect(expectLastCall()).once();
        expect(mockCommunicator.isConnected()).andReturn(true).anyTimes();
        mockCommunicator.connect(or(eq(ConnectionDriver.JSERIALCOMM), eq(ConnectionDriver.JSSC)), eq(port), eq(portRate));
        expect(instance.isCommOpen()).andReturn(false).once();
        expect(instance.isCommOpen()).andReturn(true).anyTimes();
        expect(instance.handlesAllStateChangeEvents()).andReturn(handleStateChange).anyTimes();
    }
    private void streamInstanceExpectUtility() throws Exception {
        expect(mockCommunicator.areActiveCommands()).andReturn(false).anyTimes();
        instance.isReadyToStreamCommandsEvent();
        expect(expectLastCall()).once();
        mockCommunicator.streamCommands();
        expect(expectLastCall()).once();
    }
    private void startStreamExpectation(String port, int rate) throws Exception {
        openInstanceExpectUtility(port, rate, false);
        streamInstanceExpectUtility();
        
        // Making sure the commands get queued.
        mockCommunicator.queueStreamForComm(anyObject(IGcodeStreamReader.class));
        expect(expectLastCall()).times(1);
    }
    private void startStream(String port, int rate, String command) throws Exception {
        // Open port, send some commands, make sure they are streamed.
        instance.openCommPort(getSettings().getConnectionDriver(), port, rate);
        instance.queueStream(new SimpleGcodeStreamReader(command, command));
        instance.beginStreaming();
    }
    private Settings getSettings() {
        return settings;
    }

    /**
     * Test of getCommandCreator method, of class AbstractController.
     */
    @Test
    public void testGetCommandCreator() {
        System.out.println("getCommandCreator");
        GcodeCommandCreator result = instance.getCommandCreator();
        assertEquals(gcodeCreator, result);
    }

    /**
     * Test of getSendDuration method, of class AbstractController.
     */
    @Test
    public void testGetSendDuration() throws Exception {
        System.out.println("getSendDuration");

        String command = "command";
        String port = "/some/port";
        int rate = 1234;

        startStreamExpectation(port, rate);
        expect(mockCommunicator.numActiveCommands()).andReturn(1);
        expect(mockCommunicator.numActiveCommands()).andReturn(0);
        instance.updateCommandFromResponse(anyObject(), anyString());
        expect(expectLastCall()).times(2);
        expect(instance.getControllerStatus()).andReturn(new ControllerStatus(ControllerState.IDLE, new Position(0,0,0, UnitUtils.Units.MM), new Position(0,0,0, UnitUtils.Units.MM)));
        expect(instance.getControllerStatus()).andReturn(new ControllerStatus(ControllerState.IDLE, new Position(0,0,0, UnitUtils.Units.MM), new Position(0,0,0, UnitUtils.Units.MM)));
        replay(instance, mockCommunicator);

        // Time starts at zero when nothing has been sent.
        assertEquals(0L, instance.getSendDuration());

        startStream(port, rate, command);
        long start = System.currentTimeMillis();

        Thread.sleep(1000);

        long time = instance.getSendDuration();
        long checkpoint = System.currentTimeMillis();

        // Began streaming at least 1 second ago.
        assertTrue( time > (start-checkpoint));

        Thread.sleep(1000);

        instance.commandSent(new GcodeCommand(command));
        instance.commandSent(new GcodeCommand(command));
        instance.commandComplete(command);
        instance.commandComplete(command);

        time = instance.getSendDuration();
        checkpoint = System.currentTimeMillis();

        // Completed commands after at least "checkpoint" milliseconds.
        assertTrue( time > (start-checkpoint));

        Thread.sleep(1000);

        // Make sure the time stopped after the last command was completed.
        long newtime = instance.getSendDuration();
        assertEquals( time, newtime );

        verify(mockCommunicator, instance);
    }

    /**
     * Test of queueCommand method, of class AbstractController.
     */
    @Test
    public void testQueueCommand() throws Exception {
        System.out.println("queueCommand");

        String command = "command";
        String port = "/some/port";
        int rate = 1234;

        openInstanceExpectUtility(port, rate, false);
        streamInstanceExpectUtility();
        
        // Making sure the commands get queued.
        mockCommunicator.queueStreamForComm(anyObject(IGcodeStreamReader.class));
        expect(expectLastCall()).times(1);

        replay(instance, mockCommunicator);

        // Open port, send some commands, make sure they are streamed.
        instance.openCommPort(getSettings().getConnectionDriver(), port, rate);
        instance.queueStream(new SimpleGcodeStreamReader(command, command));
        instance.beginStreaming();

        verify(mockCommunicator, instance);
    }

    /**
     * Test of queueCommands method, of class AbstractController.
     */
    @Test
    public void testQueueCommands() throws Exception {
        System.out.println("queueCommands");

        String command = "command";
        String port = "/some/port";
        int rate = 1234;

        openInstanceExpectUtility(port, rate, false);
        streamInstanceExpectUtility();
        
        // Making sure the commands get queued.
        mockCommunicator.queueStreamForComm(anyObject(IGcodeStreamReader.class));
        expect(expectLastCall()).times(1);

        replay(instance, mockCommunicator);

        // Open port, send some commands, make sure they are streamed.
        instance.openCommPort(getSettings().getConnectionDriver(), port, rate);
        instance.queueStream(new SimpleGcodeStreamReader(command, command));
        instance.beginStreaming();

        verify(mockCommunicator, instance);
    }
}
