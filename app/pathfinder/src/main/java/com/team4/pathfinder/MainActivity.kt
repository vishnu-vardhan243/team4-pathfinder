package com.team4.pathfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.team4.pathfinder.ui.PathfinderApp
import com.team4.pathfinder.ui.PathfinderTheme
import com.team4.pathfinder.ui.PathfinderViewModel

/** Pathfinder: goal chat, skill quiz, pathway, weekly plan and check-in, with Llama running on the phone. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PathfinderTheme {
                val vm: PathfinderViewModel = viewModel()
                PathfinderApp(vm)
            }
        }
    }
}
