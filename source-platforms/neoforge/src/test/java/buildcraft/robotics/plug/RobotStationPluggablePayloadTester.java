package buildcraft.robotics.plug;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.robotics.plug.RobotStationPluggable.RobotStationState;

/**
 * The render state of a robot station is computed by the server and sent to clients. A pluggable payload sent by a
 * client must not set it on the server (the server used to apply whatever byte the client sent).
 */
class RobotStationPluggablePayloadTester {
    @Test
    void serverIgnoresRenderStateSentByAClient() throws Exception {
        RobotStationPluggable plug = new RobotStationPluggable(null, null, Direction.UP);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByte(RobotStationState.Linked.ordinal());

        plug.readPayload(buffer, BCNetworkSide.SERVER, null);

        Assertions.assertEquals(RobotStationState.None, plug.getRenderState());
    }

    @Test
    void clientStillReadsTheStateSentByTheServer() throws Exception {
        RobotStationPluggable server = new RobotStationPluggable(null, null, Direction.UP);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        server.writePayload(buffer, BCNetworkSide.SERVER);
        Assertions.assertEquals(1, buffer.readableBytes(), "server payload carries the state byte");

        RobotStationPluggable client = new RobotStationPluggable(null, null, Direction.UP);
        FriendlyByteBuf fromServer = new FriendlyByteBuf(Unpooled.buffer());
        fromServer.writeByte(RobotStationState.Available.ordinal());
        client.readPayload(fromServer, BCNetworkSide.CLIENT, null);

        Assertions.assertEquals(RobotStationState.Available, client.getRenderState());
    }

    @Test
    void clientPayloadCarriesNoState() {
        RobotStationPluggable plug = new RobotStationPluggable(null, null, Direction.UP);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        plug.writePayload(buffer, BCNetworkSide.CLIENT);
        Assertions.assertEquals(0, buffer.readableBytes());
    }
}
