package com.superwall.sdk.debug

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.superwall.sdk.store.abstractions.product.StoreProduct
import android.widget.Spinner
import androidx.recyclerview.widget.LinearLayoutManager
import com.superwall.sdk.R

class SWConsoleActivity : AppCompatActivity() {
    private lateinit var productPicker: Spinner
    private lateinit var tableView: RecyclerView
    private lateinit var productAdapter: ArrayAdapter<String>
    private lateinit var tableViewAdapter: TableViewAdapter
    private var products: List<StoreProduct> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        productPicker = findViewById(R.id.productPicker)
        tableView = findViewById(R.id.console_recycler_view) // Replace with your actual RecyclerView ID

        products = intent.getSerializableExtra("products") as? List<StoreProduct>
            ?: throw IllegalArgumentException("Products not provided")

        // Set up the Spinner with the product names or identifiers
        productAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            products.map { it.productIdentifier }
        )
        productAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        productPicker.adapter = productAdapter
        productPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                // When a product is selected, update the RecyclerView with its attributes
                tableViewAdapter.updateData(products[position].attributes, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // This can be left empty
            }
        }

        // Set up the RecyclerView to display the attributes of the selected product
        setupTableView()
    }

    private fun setupTableView() {
        tableView.layoutManager = LinearLayoutManager(this)
        tableViewAdapter = TableViewAdapter(mutableListOf(), 0)
        tableView.adapter = tableViewAdapter
    }
}