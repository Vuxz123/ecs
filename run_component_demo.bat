@echo off
REM Batch script to compile and run ComponentManagerDemo independently

echo Compiling Component System files...

REM Create output directory
if not exist "build\component_demo\classes" mkdir build\component_demo\classes

REM Compile all component system files (without --release flag)
javac --enable-preview -source 17 ^
  -d build\component_demo\classes ^
  src\main\java\com\ethnicthv\ecs\core\Component.java ^
  src\main\java\com\ethnicthv\ecs\core\ComponentDescriptor.java ^
  src\main\java\com\ethnicthv\ecs\core\ComponentHandle.java ^
  src\main\java\com\ethnicthv\ecs\core\ComponentManager.java ^
  src\main\java\com\ethnicthv\ecs\components\PositionComponent.java ^
  src\main\java\com\ethnicthv\ecs\components\VelocityComponent.java ^
  src\main\java\com\ethnicthv\ecs\components\TransformComponent.java ^
  src\main\java\com\ethnicthv\ecs\components\HealthComponent.java ^
  src\main\java\com\ethnicthv\ecs\demo\ComponentManagerDemo.java

if %ERRORLEVEL% NEQ 0 (
    echo Compilation failed!
    pause
    exit /b 1
)

echo.
echo Compilation successful! Running demo...
echo.
echo ================================================
echo.

REM Run the demo
java --enable-preview -cp build\component_demo\classes com.ethnicthv.ecs.demo.ComponentManagerDemo

echo.
echo ================================================
echo.
pause
