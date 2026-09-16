package dev.anonymous.cardsdesignerpro.app.ui.unusedcards

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityUnusedCardsGuideBinding

class UnusedCardsGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnusedCardsGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnusedCardsGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }
}
