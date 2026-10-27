package io.github.umislat.kernelsu.data.repository

import io.github.umislat.kernelsu.data.model.Module
import io.github.umislat.kernelsu.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
