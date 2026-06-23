package com.wendziin.streamvault.castplugin;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView textStatus;
    private TextView textAddress;
    private EditText editUrl;
    private Button btnTest;
    private TextView textResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus = findViewById(R.id.textStatus);
        textAddress = findViewById(R.id.textAddress);
        editUrl = findViewById(R.id.editUrl);
        btnTest = findViewById(R.id.btnTest);
        textResult = findViewById(R.id.textResult);

        // Garante que o servidor proxy está iniciado localmente
        LocalProxyServer.getInstance().start();

        updateStatusUI();

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputUrl = editUrl.getText().toString().trim();
                if (!inputUrl.isEmpty()) {
                    String proxyUrl = LocalProxyServer.getInstance().getProxyUrl(inputUrl);
                    textResult.setText("Link Proxy:\n" + proxyUrl);
                } else {
                    textResult.setText("Por favor, insira uma URL válida.");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        String ip = LocalProxyServer.getInstance().getLocalIpAddress();
        int port = LocalProxyServer.getInstance().getPort();
        textAddress.setText("Endereço local: http://" + ip + ":" + port);
        textStatus.setText("Ativo");
        textStatus.setTextColor(0xFF00FF00); // Verde
    }
}
