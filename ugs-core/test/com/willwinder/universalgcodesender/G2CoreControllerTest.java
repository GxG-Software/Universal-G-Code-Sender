/*
    Copyright 2018-2022 Will Winder

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

import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerListener;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.CommunicatorState;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test G2Core controller implementation
 *
 * @author Joacim Breiler
 */
public class G2CoreControllerTest {

    @Mock
    private AbstractCommunicator communicator;

    private G2CoreController controller;
    private ArgumentCaptor<GcodeCommand> queueCommandArgumentCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        controller = new G2CoreController(communicator);

        queueCommandArgumentCaptor = ArgumentCaptor.forClass(GcodeCommand.class);
        doNothing().when(communicator).queueCommand(queueCommandArgumentCaptor.capture());
    }

    @Test
    public void rawResponseWithReadyResponse() throws Exception {
        // When
        controller.rawResponseHandler("{\"r\":{\"msg\":\"SYSTEM READY\"}}");

        // Then
        verify(communicator).sendByteImmediately(TinyGUtils.COMMAND_ENQUIRE_STATUS);
    }

    @Test
    public void rawResponseWithAckResponse() {
        // When
        controller.rawResponseHandler("{\"ack\":true}");

        // Then
        verify(communicator, times(12)).queueCommand(any(GcodeCommand.class));
        verify(communicator).streamCommands();

        assertEquals("{ej:1}", queueCommandArgumentCaptor.getAllValues().get(0).getCommandString());
        assertEquals("{sr:{posx:t, posy:t, posz:t, mpox:t, mpoy:t, mpoz:t, plan:t, vel:t, unit:t, stat:t, dist:t, admo:t, frmo:t, coor:t}}", queueCommandArgumentCaptor.getAllValues().get(1).getCommandString());
        assertEquals("{jv:4}", queueCommandArgumentCaptor.getAllValues().get(2).getCommandString());
        assertEquals("{qv:0}", queueCommandArgumentCaptor.getAllValues().get(3).getCommandString());
        assertEquals("{sv:1}", queueCommandArgumentCaptor.getAllValues().get(4).getCommandString());
        assertEquals("$$", queueCommandArgumentCaptor.getAllValues().get(5).getCommandString());
        assertEquals("{mfoe:1}", queueCommandArgumentCaptor.getAllValues().get(6).getCommandString());
    }

    @Test
    public void rawResponseWithStatusReport() {
        // Given
        ControllerListener controllerListener = mock(ControllerListener.class);
        controller.addListener(controllerListener);

        // When
        controller.rawResponseHandler("{\"r\":{\"sr\":{\"stat\":5}}}");

        // Then
        assertEquals(ControllerState.RUN, controller.getControllerStatus().getState());
        verify(controllerListener, times(0)).commandComplete(any());
    }

    @Test
    public void rawResponseWithResultForNoCommandShouldNotDispatchCommandComplete() {
        // Given
        ControllerListener controllerListener = mock(ControllerListener.class);
        controller.addListener(controllerListener);

        // When
        controller.rawResponseHandler("{\"r\":{}}");

        // Then
        verify(controllerListener, times(0)).commandComplete(any());
    }

    @Test
    public void rawResponseWithResultForCommandShouldDispatchCommandComplete() {
        // Given
        ControllerListener controllerListener = mock(ControllerListener.class);
        controller.addListener(controllerListener);
        controller = spy(controller);

        // Simulate that a command has been sent
        controller.commandSent(new GcodeCommand("test"));
        when(controller.rowsRemaining()).thenReturn(1);

        // When
        controller.rawResponseHandler("{\"r\":{}}");

        // Then
        verify(controllerListener, times(1)).commandComplete(any());
    }

    @Test
    public void rawResponseWithStatusReportStateFromControllerShouldUpdateControllerState() {
        assertEquals("The controller should begin in an disconnected state", ControllerState.DISCONNECTED, controller.getControllerStatus().getState());

        ControllerListener controllerListener = mock(ControllerListener.class);
        controller.addListener(controllerListener);

        controller.rawResponseHandler("{\"sr\":{\"stat\": 1}}");
        assertEquals(ControllerState.IDLE, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 2}}");
        assertEquals(ControllerState.ALARM, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 3}}");
        assertEquals(ControllerState.IDLE, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 4}}");
        assertEquals(ControllerState.IDLE, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 5}}");
        assertEquals(ControllerState.RUN, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_SENDING, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 6}}");
        assertEquals(ControllerState.HOLD, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_SENDING_PAUSED, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 7}}");
        assertEquals(ControllerState.UNKNOWN, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 8}}");
        assertEquals(ControllerState.UNKNOWN, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 9}}");
        assertEquals(ControllerState.HOME, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 10}}");
        assertEquals(ControllerState.JOG, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_SENDING, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 11}}");
        assertEquals(ControllerState.UNKNOWN, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 12}}");
        assertEquals(ControllerState.UNKNOWN, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());

        controller.rawResponseHandler("{\"sr\":{\"stat\": 13}}");
        assertEquals(ControllerState.ALARM, controller.getControllerStatus().getState());
        assertEquals(CommunicatorState.COMM_IDLE, controller.getCommunicatorState());
    }

    @Test
    public void cancelSend() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);
        InOrder orderVerifier = inOrder(communicator);

        // When
        controller.cancelSend();

        // Then
        orderVerifier.verify(communicator).cancelSend();
        orderVerifier.verify(communicator).sendByteImmediately(TinyGUtils.COMMAND_KILL_JOB);
        orderVerifier.verify(communicator).cancelSend(); // Work around for clearing buffers and counters in communicator
        orderVerifier.verify(communicator).queueCommand(any(GcodeCommand.class));
        orderVerifier.verify(communicator).streamCommands();

        GcodeCommand command = queueCommandArgumentCaptor.getAllValues().get(0);
        assertEquals(TinyGUtils.COMMAND_KILL_ALARM_LOCK, command.getCommandString());
    }

    @Test
    public void jogMachine() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);

        // Simulate that the machine is running in inches
        controller.getCurrentGcodeState().units = Code.G21;

        // When
        InOrder orderVerifier = inOrder(communicator);
        controller.jogMachine(new PartialPosition(100., 100., 100., UnitUtils.Units.MM), 1000);

        // Then
        orderVerifier.verify(communicator, times(1)).queueCommand(any(GcodeCommand.class));
        orderVerifier.verify(communicator).streamCommands();

        GcodeCommand command = queueCommandArgumentCaptor.getAllValues().get(0);
        assertEquals("G21G91G1X100Y100Z100F1000", command.getCommandString());
        assertTrue(command.isGenerated());
        assertTrue(command.isTemporaryParserModalChange());
    }

    @Test
    public void jogMachineWhenUsingInchesShouldConvertCoordinates() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);

        // Simulate that the machine is running in inches
        controller.getCurrentGcodeState().units = Code.G20;

        // When
        InOrder orderVerifier = inOrder(communicator);
        controller.jogMachine(new PartialPosition(100., 100., 100., UnitUtils.Units.MM), 1000);

        // Then
        orderVerifier.verify(communicator, times(1)).queueCommand(any(GcodeCommand.class));
        orderVerifier.verify(communicator).streamCommands();

        GcodeCommand command = queueCommandArgumentCaptor.getAllValues().get(0);
        assertEquals("G20G91G1X3.937Y3.937Z3.937F39.37", command.getCommandString());
        assertTrue(command.isGenerated());
        assertTrue(command.isTemporaryParserModalChange());
    }

    @Test
    public void jogMachineTo() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);

        // Simulate that the machine is running in mm
        controller.getCurrentGcodeState().units = Code.G21;

        // When
        InOrder orderVerifier = inOrder(communicator);
        controller.jogMachineTo(new PartialPosition(1.0, 2.0, 3.0, UnitUtils.Units.MM), 1000);

        // Then
        orderVerifier.verify(communicator, times(1)).queueCommand(any(GcodeCommand.class));
        orderVerifier.verify(communicator).streamCommands();

        GcodeCommand command = queueCommandArgumentCaptor.getAllValues().get(0);
        assertEquals("G21G90G1X1Y2Z3F1000", command.getCommandString());
        assertTrue(command.isGenerated());
        assertTrue(command.isTemporaryParserModalChange());
    }

    @Test
    public void jogMachineToWhenUsingInchesShouldConvertCoordinates() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);

        // Simulate that the machine is running in inches
        controller.getCurrentGcodeState().units = Code.G20;

        // When
        InOrder orderVerifier = inOrder(communicator);
        controller.jogMachineTo(new PartialPosition(1.0, 2.0, 3.0, UnitUtils.Units.MM), 1000);

        // Then
        orderVerifier.verify(communicator, times(1)).queueCommand(any(GcodeCommand.class));
        orderVerifier.verify(communicator).streamCommands();

        GcodeCommand command = queueCommandArgumentCaptor.getAllValues().get(0);
        assertEquals("G20G90G1X0.039Y0.079Z0.118F39.37", command.getCommandString());
        assertTrue(command.isGenerated());
        assertTrue(command.isTemporaryParserModalChange());
    }

    @Test
    public void jogMachineShouldEmulateRunStateAsJog() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);
        controller.jogMachine(new PartialPosition(1.0, 2.0, 3.0, UnitUtils.Units.MM), 1000d);

        // When
        controller.rawResponseHandler("{\"sr\":{\"stat\": 5}}"); // receive RUN

        // Then
        assertEquals(ControllerState.JOG, controller.getControllerStatus().getState());
    }

    @Test
    public void jogMachineShouldTurnBackToIdleWhenDone() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);
        controller.jogMachine(new PartialPosition(1.0, 2.0, 3.0, UnitUtils.Units.MM), 1000d);
        controller.rawResponseHandler("{\"sr\":{\"stat\": 1}}"); // receive IDLE

        // Then
        assertEquals(ControllerState.IDLE, controller.getControllerStatus().getState());
    }

    @Test
    public void jogMachineShouldTurnSwitchOfIsJoggingWhenComplete() throws Exception {
        // Given
        when(communicator.isConnected()).thenReturn(true);
        controller.jogMachine(new PartialPosition(1.0, 2.0, 3.0, UnitUtils.Units.MM), 1000d);
        controller.rawResponseHandler("{\"sr\":{\"stat\": 1}}"); // receive IDLE

        // When
        controller.sendCommandImmediately(new GcodeCommand("G0 X1"));
        controller.rawResponseHandler("{\"sr\":{\"stat\": 5}}"); // receive RUN

        // Then
        assertEquals("Should now consider send commands as a normal run state", ControllerState.RUN, controller.getControllerStatus().getState());
    }

    @Test
    public void commandCompleteShouldDispatchCommandEvent() throws Exception {
        AtomicBoolean eventDispatched = new AtomicBoolean(false);
        GcodeCommand command = new GcodeCommand("{}");
        command.addListener(c -> eventDispatched.set(true));

        // Simulate sending and completing the command
        controller.commandSent(command);
        controller.commandComplete("{}");

        assertTrue("Should have sent an event notifying that the command has completed", eventDispatched.get());
    }
}
