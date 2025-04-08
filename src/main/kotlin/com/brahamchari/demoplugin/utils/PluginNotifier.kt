package com.brahamchari.demoplugin.utils // Adjust package name as needed

import com.intellij.notification.* // Core notification classes
import com.intellij.openapi.actionSystem.AnAction // For actions
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import javax.swing.Icon // For optional icons
import com.intellij.openapi.util.NlsContexts // For annotated strings (optional but good practice)

/**
 * Singleton utility object for displaying notifications within the IDE.
 *
 * Replace "YourPlugin.NotificationGroup" with your chosen unique ID.
 */
object PluginNotifier {

    private const val GROUP_DISPLAY_ID = "com.brahamchari.demoplugin.notifications"
    // Get the registered notification group instance
    // Use NotificationGroupManager for persistent balloon notifications that are logged.
    private val NOTIFICATION_GROUP: NotificationGroup = NotificationGroupManager.getInstance()
        .getNotificationGroup(GROUP_DISPLAY_ID)

    /**
     * Shows an information notification.
     *
     * @param project The project context, or null for application-level notifications.
     * @param title The title of the notification.
     * @param content The main message content (can include basic HTML).
     * @param actions Optional list of actions to add to the notification.
     * @param icon Optional icon for the notification.
     */
    fun showInfo(
        project: Project?,
        @NlsContexts.NotificationTitle title: String,
        @NlsContexts.NotificationContent content: String,
        actions: List<AnAction> = emptyList(),
        icon: Icon? = null // You can use icons from AllIcons (e.g., AllIcons.General.Information)
    ) {
        showNotification(project, title, content, NotificationType.INFORMATION, actions, icon)
    }

    /**
     * Shows a warning notification.
     *
     * @param project The project context, or null for application-level notifications.
     * @param title The title of the notification.
     * @param content The main message content (can include basic HTML).
     * @param actions Optional list of actions to add to the notification.
     * @param icon Optional icon for the notification.
     */
    fun showWarning(
        project: Project?,
        @NlsContexts.NotificationTitle title: String,
        @NlsContexts.NotificationContent content: String,
        actions: List<AnAction> = emptyList(),
        icon: Icon? = null // You can use icons from AllIcons (e.g., AllIcons.General.Warning)
    ) {
        showNotification(project, title, content, NotificationType.WARNING, actions, icon)
    }

    /**
     * Shows an error notification.
     *
     * @param project The project context, or null for application-level notifications.
     * @param title The title of the notification.
     * @param content The main message content (can include basic HTML).
     * @param actions Optional list of actions to add to the notification.
     * @param icon Optional icon for the notification.
     */
    fun showError(
        project: Project?,
        @NlsContexts.NotificationTitle title: String,
        @NlsContexts.NotificationContent content: String,
        actions: List<AnAction> = emptyList(),
        icon: Icon? = null // You can use icons from AllIcons (e.g., AllIcons.General.Error)
    ) {
        showNotification(project, title, content, NotificationType.ERROR, actions, icon)
    }

    /**
     * Helper function to create and show the notification.
     */
    private fun showNotification(
        project: Project?,
        @NlsContexts.NotificationTitle title: String,
        @NlsContexts.NotificationContent content: String,
        type: NotificationType,
        actions: List<AnAction>,
        icon: Icon?
    ) {
        // Create the notification object
        val notification = NOTIFICATION_GROUP.createNotification(title, content, type)

        // Set icon if provided
        icon?.let { notification.setIcon(it) }

        // Add actions
        actions.forEach { notification.addAction(it) }

        // Show the notification
        // Passing the project makes it context-aware (e.g., potentially shown only for that project window)
        notification.notify(project)

        // Alternative using Notifications.Bus directly (gives less control over group settings like persistence)
        // Notifications.Bus.notify(notification, project)

        println("Showing Notification: [$type] '$title' - $content (Project: ${project?.name ?: "App"})") // For debugging
    }

     /**
      * Simpler convenience function when the handler doesn't need the Notification instance.
      */
     fun createSimpleAction(
         @NlsContexts.NotificationContent actionText: String,
         handler: () -> Unit // Simpler lambda without arguments
     ): AnAction {
         return NotificationAction.createSimple(actionText, handler) // Use the overload that takes Runnable
     }

    /**
     * Creates a notification action where the handler requires both the AnActionEvent
     * and the specific Notification instance that was clicked.
     *
     * @param actionText The text displayed for the action link.
     * @param handler The lambda function to execute. Receives AnActionEvent and Notification. Runs on EDT.
     * @return An AnAction instance.
     */
    fun createFullAction(
        @NlsContexts.NotificationContent actionText: String,
        handler: (event: AnActionEvent, notification: Notification) -> Unit // Lambda receives both
    ): AnAction {
        return object : NotificationAction(actionText) {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                println("FullAction: Action performed. Event: $e, Notification: $notification")
                // Call the handler with both arguments
                handler(e, notification)
                // Example: Expire notification after handling
                // notification.expire()
            }
        }
    }

    /**
     * Creates a notification action where the handler requires the AnActionEvent.
     * The handler does NOT receive the Notification instance reliably here.
     *
     * @param actionText The text displayed for the action link.
     * @param handler The lambda function to execute. Receives the AnActionEvent. Runs on EDT.
     * @return An AnAction instance.
     */
    fun createEventAction(
        @NlsContexts.NotificationContent actionText: String,
        handler: (event: AnActionEvent) -> Unit // Lambda receives AnActionEvent
    ): AnAction {
        // Subclass NotificationAction directly
        return object : NotificationAction(actionText) {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                // The 'notification' parameter IS provided here by the platform
                println("EventAction: Action performed. Event: $e, Notification: $notification")
                // Call the handler, passing only the event
                handler(e)
                // You could expire the notification after handling if needed:
                // notification.expire()
            }
        }
    }
}