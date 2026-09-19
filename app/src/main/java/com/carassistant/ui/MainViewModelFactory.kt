package com.carassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * ViewModel 工厂。
 *
 * 通过 CreationExtras 取 Application，而不是持有 Activity 引用 ——
 * 车机 App 常驻时间长，泄漏 Activity 的代价比手机端更大。
 */
class MainViewModelFactory : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = checkNotNull(
            extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
        ) { "无法获取 Application 实例" }

        @Suppress("UNCHECKED_CAST")
        return MainViewModel(application.applicationContext) as T
    }

    companion object {
        fun create(): MainViewModelFactory = MainViewModelFactory()
    }
}