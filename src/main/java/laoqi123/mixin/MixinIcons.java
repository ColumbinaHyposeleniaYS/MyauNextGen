package laoqi123.mixin;

import net.minecraft.client.util.Icons;
import org.spongepowered.asm.mixin.Mixin;

// ---- 窗口图标替换（已停用：恢复原版 Minecraft 图标）----
// import java.io.FileNotFoundException;
// import java.io.IOException;
// import java.io.InputStream;
// import java.util.List;
// import net.minecraft.resource.InputSupplier;
// import net.minecraft.resource.ResourcePack;
// import org.spongepowered.asm.mixin.injection.At;
// import org.spongepowered.asm.mixin.injection.Inject;
// import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Icons.class)
public abstract class MixinIcons {
    // private static InputSupplier<InputStream> myauIcon(String fileName) {
    //     return () -> {
    //         InputStream inputStream = MixinIcons.class.getClassLoader().getResourceAsStream("assets/myaunextgen/icons/" + fileName);
    //         if (inputStream == null) {
    //             throw new FileNotFoundException("assets/myaunextgen/icons/" + fileName);
    //         }
    //         return inputStream;
    //     };
    // }
    //
    // @Inject(method = "getIcons", at = @At("HEAD"), cancellable = true)
    // private void myauGetIcons(ResourcePack resourcePack, CallbackInfoReturnable<List<InputSupplier<InputStream>>> cir) {
    //     cir.setReturnValue(List.of(myauIcon("icon_16x16.png"), myauIcon("icon_64x64.png")));
    // }
}
