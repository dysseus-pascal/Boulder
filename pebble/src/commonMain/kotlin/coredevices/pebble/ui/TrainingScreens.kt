package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Trainingsansicht von Kieselsport. Sie liest Health Connect und
 * zeigt Karten mit osmdroid - beides gibt es nur auf Android; dort steht die
 * Umsetzung (coredevices.pebble.kiesel.ansicht).
 */
@Composable
expect fun TrainingScreen(topBarParams: TopBarParams, nav: NavBarNav)

@Composable
expect fun TrainingDetailScreen(topBarParams: TopBarParams, nav: NavBarNav, id: String)
