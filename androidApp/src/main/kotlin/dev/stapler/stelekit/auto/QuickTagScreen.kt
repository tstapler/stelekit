// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickTagScreen(
    carContext: CarContext,
    private val writer: NoteWriter,
    private val observer: ObservedSession,
    private val settings: AudiobookAutoSettings,
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tags: List<String> = settings.getQuickTags()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()
        tags.forEach { tag ->
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(tag)
                    .setOnClickListener { onTagSelected(tag) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Quick Tag")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun onTagSelected(tag: String) {
        observer.refreshBookInfo()
        val bookInfo = observer.bookInfo.value
        screenScope.launch {
            writer.writeNote(AudiobookNote.QuickTagNote(tag = tag, bookInfo = bookInfo))
            screenManager.pop()
        }
    }
}
