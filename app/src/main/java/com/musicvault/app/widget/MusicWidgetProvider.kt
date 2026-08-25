package com.musicvault.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.musicvault.app.R

class MusicWidgetProvider: AppWidgetProvider(){
 override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){
  ids.forEach{ id -> m.updateAppWidget(id, RemoteViews(c.packageName, android.R.layout.simple_list_item_1)) }
 }
}
