package com.employee.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

data class NodeData(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    var bounds: String,
    val children: MutableList<NodeData>
)

class SocketServer(private val service: AccessibilityService) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val gson = Gson()

    fun start(port: Int) {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d("Agent", "Server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept()
                    client?.let {
                        executor.submit { handleClient(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("Agent", "Server error", e)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("Agent", "Error closing server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = PrintWriter(socket.getOutputStream(), true)

            val command = input.readLine()
            Log.d("Agent", "Received command: $command")

            if (command == "DUMP") {
                val root = service.rootInActiveWindow
                if (root != null) {
                    val dump = serializeNode(root)
                    output.println(gson.toJson(dump))
                } else {
                    output.println("ERROR: No root window")
                }
            } else {
                output.println("UNKNOWN COMMAND")
            }

            socket.close()
        } catch (e: Exception) {
            Log.e("Agent", "Client handling error", e)
        }
    }

    private fun serializeNode(node: AccessibilityNodeInfo): NodeData {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val data = NodeData(
            className = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
            children = mutableListOf()
        )

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                data.children.add(serializeNode(child))
            }
        }
        return data
    }
}
