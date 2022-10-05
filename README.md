# upgrader

Android in-app upgrader

## Installation

```groovy

repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.Zxilly:upgrader:master-SNAPSHOT'
}
```

## Usage

create a class implements `Checker`, ans

```kotlin

class MyApplicaiton : Application() {
    override fun onCreate() {
        super.onCreate()
        Upgrader(yourCheckerInstance, this)
    }
}
```

By default, it will auto check for update. You can also check for update manually.

```kotlin

(application as MyApplicaiton).upgrader.tryUpgrade()

```

You can find some checker in `dev.zxilly.upgrader.checker`.

## License

[MIT](https://choosealicense.com/licenses/mit/)