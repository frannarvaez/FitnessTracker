package com.snabbt.fitnesstracker.fitness_tracker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.snabbt.fitnesstracker.fitness_tracker.databinding.ActivityPermissionsRationaleBinding

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPermissionsRationaleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.closeButton.setOnClickListener { finish() }
    }
}
