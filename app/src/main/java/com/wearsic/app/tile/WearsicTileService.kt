package com.wearsic.app.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * System Tile: brand header plus prev / play-pause / next text controls that
 * command background playback without opening the app.
 */
class WearsicTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        fun controlText(label: String, tileAction: String): LayoutElementBuilders.LayoutElement {
            val activity = ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName("com.wearsic.app.MainActivity")
                .addKeyToExtraMapping("tile_action", ActionBuilders.stringExtra(tileAction))
                .build()
            val click = ModifiersBuilders.Clickable.Builder()
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity).build()
                )
                .build()
            val mods = ModifiersBuilders.Modifiers.Builder().setClickable(click).build()
            return LayoutElementBuilders.Text.Builder()
                .setText(label)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(22f))
                        .build()
                )
                .setModifiers(mods)
                .build()
        }

        val row = LayoutElementBuilders.Row.Builder()
            .addContent(controlText("  ◀  ", "prev"))
            .addContent(controlText("  ▶  ", "toggle"))
            .addContent(controlText("  ▶|  ", "next"))
            .build()

        val column = LayoutElementBuilders.Box.Builder()
            .addContent(row)
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(column).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    protected override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        )
    }
}