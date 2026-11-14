package com.example.lootguard.mixin;

import com.example.lootguard.LootGuard;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
    // Paket netty thread'inde gelir gelmez (ana thread'e siraya girmeden once) LootGuard'a ver.
    // require = 0: hedef metot bu surumde bulunamazsa oyun cokmez, sadece ana thread taramasi calisir.
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"), require = 0)
    private void lootguard$onPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        LootGuard.onPacketNetty(packet);
    }
}
