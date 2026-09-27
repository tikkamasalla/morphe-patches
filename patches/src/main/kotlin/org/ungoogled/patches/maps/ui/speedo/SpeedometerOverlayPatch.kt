package org.ungoogled.patches.maps.ui.speedo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.Method
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * Genuine 26.36 target, mapped from the app's own dex (obfuscated names!).
 *
 * Lahiv.f()Ljava/lang/Boolean; is THE show-decision for the speed overlay:
 * TRUE unless settings/eligibility say otherwise. The patched code calls
 * Shapes.speedoForce() first: when the user switched the overlay on, it
 * answers TRUE immediately; when off, Google's own code runs untouched.
 */
private fun isLahivShowDecision(classType: String, method: Method): Boolean {
    if (classType != "Lahiv;") return false
    if (method.name != "f" || method.returnType != "Ljava/lang/Boolean;") return false
    return method.implementation != null
}

@Suppress("unused")
val speedometerOverlayPatch = bytecodePatch(
    name = "Speedometer overlay",
    description = "Always shows the speed overlay while navigating. " +
        "Switch it on or off on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        markPatched("speedoPatched")

        var foundF = 0
        classDefForEach { classDef ->
            for (method in classDef.methods) {
                if (!isLahivShowDecision(classDef.type, method)) continue
                foundF++
                val mutableClass = mutableClassDefBy(classDef.type)
                val mutableMethod = mutableClass.methods.single {
                    it.name == method.name && it.parameterTypes == method.parameterTypes &&
                        it.returnType == method.returnType
                }
                val impl = mutableMethod.implementation
                    ?: throw PatchException("Lahiv.f has no implementation")
                if (impl.registerCount < 1) {
                    throw PatchException("Lahiv.f has no free register")
                }
                // v0 must be a local: the original code runs after it when off.
                val parameterRegisters =
                    method.parameterTypes.size +
                        method.parameterTypes.count { it == "J" || it == "D" }
                if (impl.registerCount - parameterRegisters < 1) {
                    throw PatchException("Lahiv.f has no free local register")
                }
                mutableMethod.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, Lorg/ungoogled/ui/Shapes;->speedoForce()Ljava/lang/Boolean;
                        move-result-object v0
                        if-eqz v0, :google_decision
                        return-object v0
                    """.trimIndent(),
                    ExternalLabel(
                        "google_decision",
                        impl.instructions.first(),
                    ),
                )
            }
        }
        if (foundF != 1) {
            throw PatchException("expected exactly one Lahiv.f()Boolean, found $foundF")
        }
    }
}
