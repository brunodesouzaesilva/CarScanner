# OBD2 Monitor — protótipo Android BLE

Aplicativo experimental Android (Kotlin) para ELM327 BLE. Apenas leitura. Não altera configurações da ECU e não apaga DTCs.

## Compilar sem computador
1. Crie um repositório GitHub (privado, se preferir).
2. Envie **o conteúdo desta pasta** para a raiz do repositório, preservando `.github/workflows/android.yml`.
3. Abra **Actions > Build Android APK > Run workflow** (ou faça push para `main`).
4. Ao terminar, abra a execução e baixe o artifact `OBD2Monitor-debug-apk`.
5. Extraia o ZIP do artifact no Android e instale `app-debug.apk` (autorize instalação da origem, se necessário).

## Uso
- Desconecte o Car Scanner antes de abrir este app.
- Ligue a ignição, mantenha o adaptador conectado e ative o Bluetooth.
- Toque em **Buscar OBDII**, aceite as permissões e aguarde a inicialização.
- Toque em **Iniciar leituras**. **Consultar falhas** mostra a resposta DTC bruta no registro.
- Se a conexão falhar, copie o registro de UUIDs para diagnóstico.

## Limitações desta versão
- Não testado em um adaptador real; alguns BLE usam características/protocolos proprietários e podem exigir ajustes.
- A identificação automática dos canais GATT é heurística.
- O registro não é persistente, não há exportação automática, nem decodificação completa de DTC.
- Não usa IA, internet ou API da OpenAI.
- O scan filtra dispositivos por nome OBD/ELM/V-LINK. Alguns adaptadores usam outros nomes.
- Em Android 12+, a permissão de busca com `neverForLocation` pode filtrar certos dispositivos BLE.
- Evite usar o app enquanto dirige; faça os testes com o veículo estacionado e ventilação adequada.
