@echo off
echo ==============================================
echo ⚙️ Construyendo el ejecutable del Nodo ADSOA
echo ==============================================

if not exist ".\Scripts\pyinstaller.exe" (
    echo [!] No se encontro PyInstaller. Instalando...
    .\Scripts\pip.exe install pyinstaller
)

echo [*] Compilando con PyInstaller...
.\Scripts\pyinstaller.exe --onefile --name NodoDataField src\main.py

echo.
echo [✅] Construccion finalizada!
echo Puedes encontrar tu programa en la carpeta: dist\NodoDataField.exe
echo.
pause

