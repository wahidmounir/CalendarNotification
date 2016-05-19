//
//   Calendar Notifications Plus  
//   Copyright (C) 2016 Sergey Parshin (s.parshin.sc@gmail.com)
//
//   This program is free software; you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation; either version 3 of the License, or
//   (at your option) any later version.
// 
//   This program is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with this program; if not, write to the Free Software Foundation,
//   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
//

package com.github.quarck.calnotify.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.github.quarck.calnotify.Consts
import com.github.quarck.calnotify.NotificationSettingsSnapshot
import com.github.quarck.calnotify.Settings
import com.github.quarck.calnotify.calendar.CalendarUtils
import com.github.quarck.calnotify.eventsstorage.EventDisplayStatus
import com.github.quarck.calnotify.eventsstorage.EventRecord
import com.github.quarck.calnotify.eventsstorage.EventsStorage
import com.github.quarck.calnotify.eventsstorage.formatText
import com.github.quarck.calnotify.globalState
import com.github.quarck.calnotify.logs.Logger
import com.github.quarck.calnotify.pebble.PebbleUtils
import com.github.quarck.calnotify.quiethours.QuietHoursManager
import com.github.quarck.calnotify.ui.ActivityMain
import com.github.quarck.calnotify.ui.ActivitySnooze
import com.github.quarck.calnotify.utils.backgroundWakeLocked
import com.github.quarck.calnotify.utils.powerManager

interface IEventNotificationManager {
    fun onEventAdded(ctx: Context, event: EventRecord);

    fun onEventDismissed(ctx: Context, eventId: Long, notificationId: Int);

    fun onEventSnoozed(ctx: Context, eventId: Long, notificationId: Int);

    fun postEventNotifications(context: Context, force: Boolean, primaryEventId: Long?);

    fun fireEventReminder(context: Context)
}

class EventNotificationManager : IEventNotificationManager {

    override fun onEventAdded(
        ctx: Context,
        event: EventRecord
    ) {
        postEventNotifications(ctx, false, event.eventId);
    }

    override fun onEventDismissed(ctx: Context, eventId: Long, notificationId: Int) {
        //
        removeNotification(ctx, eventId, notificationId);
        postEventNotifications(ctx, false, null);
    }

    override fun onEventSnoozed(ctx: Context, eventId: Long, notificationId: Int) {
        //
        removeNotification(ctx, eventId, notificationId);
        postEventNotifications(ctx, false, null);
    }

    fun wakeScreenIfRequired(ctx: Context, settings: Settings) {

        if (settings.notificationWakeScreen) {
            //
            backgroundWakeLocked(
                ctx.powerManager,
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                Consts.SCREEN_WAKE_LOCK_NAME) {
                // Screen would actually be turned on for a duration of screen timeout set by the user
                // So don't need to keep wakelock for too long
                Thread.sleep(Consts.WAKE_SCREEN_DURATION);
            }
        }

    }

    override fun postEventNotifications(context: Context, force: Boolean, primaryEventId: Long?) {
        //
        var settings = Settings(context)

        var currentTime = System.currentTimeMillis()

        var isQuietPeriodActive = QuietHoursManager.getSilentUntil(settings) != 0L

        var postedAnyNotification = false

        EventsStorage(context).use {
            db ->

            // events with snoozedUntil == 0 are currently visible ones
            // events with experied snoozedUntil are the ones to beep about
            // everything else should be hidden and waiting for the next alarm

            var activeEvents =
                db.events
                    .filter {
                        (it.snoozedUntil == 0L)
                            || (it.snoozedUntil < currentTime + Consts.ALARM_THRESHOULD)
                    }
                    .sortedBy { it.lastEventUpdate }

            var recentEvents = activeEvents.takeLast(Consts.MAX_NOTIFICATIONS - 1);
            var olderEvents = activeEvents.take(activeEvents.size - recentEvents.size)

            collapseDisplayedNotifications(context, db, olderEvents, settings, force);

            postedAnyNotification =
                postDisplayedEventNotifications(
                    context, db, settings,
                    recentEvents,
                    force, isQuietPeriodActive,
                    primaryEventId)

            if (olderEvents.isEmpty())
                hideCollapsedEventsNotification(context);
        }

        // If this is a new notification -- wake screen when required
        if (primaryEventId != null || postedAnyNotification)
            wakeScreenIfRequired(context, settings);
    }

    override fun fireEventReminder(context: Context) {

        var mostRecentEvent =
            EventsStorage(context).use {
                db ->
                db.events
                    .filter { it.snoozedUntil == 0L }
                    .maxBy { it.lastEventUpdate }
            }

        if (mostRecentEvent != null) {

            var settings = Settings(context)

            postNotification(
                context,
                mostRecentEvent,
                settings.notificationSettingsSnapshot
            )

            wakeScreenIfRequired(context, settings);
        }
    }

    private fun collapseDisplayedNotifications(
        context: Context, db: EventsStorage,
        events: List<EventRecord>, settings: Settings, force: Boolean ) {

        logger.debug("Hiding notifications for ${events.size} notification")

        for (event in events) {
            if ((event.displayStatus != EventDisplayStatus.Hidden) || force) {
                logger.debug("Hiding notification id ${event.notificationId}, eventId ${event.eventId}")
                removeNotification(context, event.eventId, event.notificationId);

                event.displayStatus = EventDisplayStatus.DisplayedCollapsed;
                db.updateEvent(event);
            } else {
                logger.debug("Skipping hiding of notification id ${event.notificationId}, eventId ${event.eventId} - already hidden");
            }
        }

        postNumNotificationsCollapsed(context, db, settings, events);
    }

    // force - if true - would re-post all active notifications. Normally only new notifications are posted to
    // avoid excessive blinking in the notifications area. Forced notifications are posted without sound or vibra
    private fun postDisplayedEventNotifications(
        context: Context,
        db: EventsStorage,
        settings: Settings,
        events: List<EventRecord>,
        force: Boolean,
        isQuietPeriodActive: Boolean,
        primaryEventId: Long?
    ): Boolean {

        logger.debug("Posting ${events.size} notifications");

        var notificationsSettings = settings.notificationSettingsSnapshot
        var notificationsSettingsQuiet = notificationsSettings.copy(ringtoneUri = null, vibraOn = false, forwardToPebble = false);

        //var isQuietPeriodActive = QuietHoursManager.getSilentUntil(settings) != 0L

        var postedNotification = false
        var playedAnySound = false

        for (event in events) {
            if (event.snoozedUntil == 0L) {
                // This should be currently displayed, if snoozedUntil is zero
                if ((event.displayStatus != EventDisplayStatus.DisplayedNormal) || force) {
                    // currently not displayed or forced -- post notifications
                    logger.debug("Posting notification id ${event.notificationId}, eventId ${event.eventId}");

                    var shouldBeQuiet = false

                    if (force) {
                        // If forced to re-post all notifications - we only have to actually display notifications
                        // so not playing sound / vibration here
                        shouldBeQuiet = true
                    } else if (event.displayStatus == EventDisplayStatus.DisplayedCollapsed) {
                        // This event was already visible as "collapsed", user just removed some other notification
                        // and so we automatically expanding some of the events, this one was lucky.
                        // No sound / vibration should be played here
                        shouldBeQuiet = true
                    } else if (isQuietPeriodActive) {
                        // we are in a silent period, normally we should always be quiet, but there
                        // are a few exclusions
                        if (primaryEventId != null && event.eventId == primaryEventId) {
                            // this is primary event -- play based on use preference for muting
                            // primary event reminders
                            shouldBeQuiet = settings.quietHoursMutePrimary
                        } else {
                            // not a primary event -- always silent in silent period
                            shouldBeQuiet = true
                        }
                    }

                    postNotification(context, event,
                        if (shouldBeQuiet) notificationsSettingsQuiet else notificationsSettings)

                    // Update db to indicate that this event is currently actively displayed
                    db.updateEvent(event, displayStatus = EventDisplayStatus.DisplayedNormal);

                    postedNotification = true
                    playedAnySound = playedAnySound || !shouldBeQuiet

                } else {
                    logger.debug("Not re-posting notification id ${event.notificationId}, eventId ${event.eventId} - already on the screen");
                }
            } else {
                // This event is currently snoozed and switching to "Shown" state

                logger.debug("Posting snoozed notification id ${event.notificationId}, eventId ${event.eventId}");

                postNotification(context, event, notificationsSettings)

                // Update Db to indicate that event is currently displayed and no longer snoozed
                // Since it is displayed now -- it is no longer snoozed, set snoozedUntil to zero also
                db.updateEvent(event, displayStatus = EventDisplayStatus.DisplayedNormal, snoozedUntil = 0);

                postedNotification = true
                playedAnySound = true
            }
        }

        if (playedAnySound)
            context.globalState.updateNotificationLastFiredTime();

        if (isQuietPeriodActive && settings.quietHoursRemindAfter
            && !settings.quietHoursOneTimeReminderEnabled) {
            settings.quietHoursOneTimeReminderEnabled = true;
        }

        return postedNotification
    }

    private fun postNotification(
        ctx: Context,
        event: EventRecord,
        notificationSettings: NotificationSettingsSnapshot
    ) {
        var notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        var calendarIntent = CalendarUtils.getCalendarViewIntent(event.eventId);
        //calendarIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK;
        //var calendarPendingIntent = PendingIntent.getActivity(ctx, 0, calendarIntent, 0)

        var calendarPendingIntent =
            TaskStackBuilder.create(ctx)
                .addNextIntentWithParentStack(calendarIntent)
                .getPendingIntent(0, 0)//PendingIntent.FLAG_UPDATE_CURRENT);

        var notificationText = event.formatText(ctx);

        var builder = Notification.Builder(ctx)
            .setContentTitle(event.title)
            .setContentText(notificationText)
            .setSmallIcon(com.github.quarck.calnotify.R.drawable.stat_notify_calendar)
            .setPriority(
                if (notificationSettings.headsUpNotification)
                    Notification.PRIORITY_HIGH
                else
                    Notification.PRIORITY_DEFAULT
            )
            .setContentIntent(calendarPendingIntent)
            .setAutoCancel(!notificationSettings.showDismissButton)
            .setOngoing(notificationSettings.showDismissButton)
            .setStyle(Notification.BigTextStyle()
                .bigText(notificationText))
            .setWhen(System.currentTimeMillis())

        logger.debug("adding pending intent for snooze, event id ${event.eventId}, notificationId ${event.notificationId}")

        builder.addAction(
            com.github.quarck.calnotify.R.drawable.ic_update_white_24dp,
            ctx.getString(com.github.quarck.calnotify.R.string.snooze) ?: "SNOOZE",
            pendingActivityIntent(ctx,
                snoozeIntent(ctx, event.eventId, event.notificationId),
                event.notificationId * 3 + 0
            )
        )

        if (notificationSettings.showDismissButton) {
            builder.addAction(
                com.github.quarck.calnotify.R.drawable.ic_clear_white_24dp,
                ctx.getString(com.github.quarck.calnotify.R.string.dismiss) ?: "DISMISS",
                pendingServiceIntent(ctx,
                    dismissOrDeleteIntent(ctx, event.eventId, event.notificationId),
                    event.notificationId * 3 + 1
                )
            )
        } else {
            builder.setDeleteIntent(
                pendingServiceIntent(ctx,
                    dismissOrDeleteIntent(ctx, event.eventId, event.notificationId),
                    event.notificationId * 3 + 2
                )
            )
        }

        if (notificationSettings.ringtoneUri != null) {
            logger.debug("Adding ringtone uri ${notificationSettings.ringtoneUri}");
            builder.setSound(notificationSettings.ringtoneUri)
        }

        if (notificationSettings.vibraOn) {
            logger.debug("adding vibration");
            builder.setVibrate(longArrayOf(0, Consts.VIBRATION_DURATION));
        } else {
            builder.setVibrate(longArrayOf(0));
        }

        if (notificationSettings.ledNotificationOn) {
            logger.debug("Adding leds")
            builder.setLights(notificationSettings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF);
        }

        var notification = builder.build()

        try {
            logger.debug(
                "adding: notificationId=${event.notificationId}, notification is ${notification}, stack:")

            notificationManager.notify(
                event.notificationId,
                notification
            )
        } catch (ex: Exception) {
            logger.error(
                "Exception: ${ex.toString()}, notificationId=${event.notificationId}, notification is ${if (notification != null) 1 else 0}, stack:")
            ex.printStackTrace()
        }

        if (notificationSettings.forwardToPebble)
            PebbleUtils.forwardNotificationToPebble(ctx, event.title, notificationText)
    }

    private fun snoozeIntent(ctx: Context, eventId: Long, notificationId: Int): Intent {

        var intent = Intent(ctx, ActivitySnooze::class.java)
        intent.putExtra(Consts.INTENT_NOTIFICATION_ID_KEY, notificationId);
        intent.putExtra(Consts.INTENT_EVENT_ID_KEY, eventId);
        return intent;
    }

    private fun dismissOrDeleteIntent(ctx: Context, eventId: Long, notificationId: Int): Intent {

        var intent = Intent(ctx, ServiceNotificationActionDismiss::class.java)
        intent.putExtra(Consts.INTENT_NOTIFICATION_ID_KEY, notificationId);
        intent.putExtra(Consts.INTENT_EVENT_ID_KEY, eventId);
        return intent;
    }

    private fun pendingServiceIntent(ctx: Context, intent: Intent, id: Int): PendingIntent
        = PendingIntent.getService(ctx, id, intent, PendingIntent.FLAG_CANCEL_CURRENT)

    private fun pendingActivityIntent(ctx: Context, intent: Intent, id: Int): PendingIntent {

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        var pendingIntent =
            TaskStackBuilder.create(ctx)
                .addNextIntentWithParentStack(intent)
                .getPendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT);
        /*
                var pendingIntent =
                        PendingIntent.getActivity(
                                ctx,
                                id,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT)
        */
        return pendingIntent
    }

    private fun removeNotification(ctx: Context, eventId: Long, notificationId: Int) {
        var notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId);
    }

    private fun postNumNotificationsCollapsed(
        context: Context,
        db: EventsStorage,
        settings: Settings,
        events: List<EventRecord>
    ) {
        logger.debug("Posting 'collapsed view' notification");

        var intent = Intent(context, ActivityMain::class.java);
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)

        var title = java.lang.String.format(
            context.getString(com.github.quarck.calnotify.R.string.multiple_events),
            events.size
        );

        val notification =
            Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(context.getString(com.github.quarck.calnotify.R.string.multiple_events_details))
                .setSmallIcon(com.github.quarck.calnotify.R.drawable.stat_notify_calendar)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setLights(settings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)
                .build()

        var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Consts.NOTIFICATION_ID_COLLAPSED, notification) // would update if already exists

        context.globalState.updateNotificationLastFiredTime()
    }

    private fun hideCollapsedEventsNotification(context: Context) {
        logger.debug("Hiding 'collapsed view' notification");

        var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Consts.NOTIFICATION_ID_COLLAPSED);
    }

    companion object {
        private val logger = Logger("EventNotificationManager")
    }
}
