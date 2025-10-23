@echo off
REM Temporarily rename old files that are incompatible with new component system

echo Renaming old incompatible files...

cd /d D:\JavaProject\ecs\src\main\java\com\ethnicthv\ecs

if exist core\World.java ren core\World.java World.java.old
if exist demo\ECSDemo.java ren demo\ECSDemo.java ECSDemo.java.old
if exist demo\ImprovedDemo.java ren demo\ImprovedDemo.java ImprovedDemo.java.old
if exist demo\PerformanceBenchmark.java ren demo\PerformanceBenchmark.java PerformanceBenchmark.java.old
if exist demo\ArchetypeDemo.java ren demo\ArchetypeDemo.java ArchetypeDemo.java.old
if exist demo\ArchetypeVsSparseSetBenchmark.java ren demo\ArchetypeVsSparseSetBenchmark.java ArchetypeVsSparseSetBenchmark.java.old
if exist systems\MovementSystem.java ren systems\MovementSystem.java MovementSystem.java.old
if exist systems\VectorizedMovementSystem.java ren systems\VectorizedMovementSystem.java VectorizedMovementSystem.java.old

echo Done! Old files have been renamed with .old extension.
echo.
echo Now running ComponentManagerDemo with Gradle...
echo.

cd /d D:\JavaProject\ecs
call gradlew.bat runComponentDemo

pause

