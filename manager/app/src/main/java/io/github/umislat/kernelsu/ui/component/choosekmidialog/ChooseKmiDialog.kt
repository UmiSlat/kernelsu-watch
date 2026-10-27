package io.github.umislat.kernelsu.ui.component.choosekmidialog

import androidx.compose.runtime.Composable
import io.github.umislat.kernelsu.ui.LocalUiMode
import io.github.umislat.kernelsu.ui.UiMode

@Composable
fun ChooseKmiDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> ChooseKmiDialogMiuix(show, onDismissRequest, onSelected)
        UiMode.Material -> ChooseKmiDialogMaterial(show, onDismissRequest, onSelected)
    }
}
