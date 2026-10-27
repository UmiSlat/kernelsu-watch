package io.github.umislat.kernelsu.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import io.github.umislat.kernelsu.ui.LocalUiMode
import io.github.umislat.kernelsu.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
