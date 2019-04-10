package com.lws.fragusekotlin

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.app.FragmentManager
import org.jetbrains.anko.support.v4.ctx
import java.util.*

private const val FRAGMENT_TAG = "EmptyFragment"

private val codeGenerator = Random()
// startActivityForResult的回调持有类
private val resultHolder = LinkedHashMap<Int, LambdaHolder<Intent>>()
// requestPermission的回调持有类
private val permissionHolder = LinkedHashMap<Int, LambdaHolder<Unit>>()

/**
 * 查找Activity中有没有EmptyFragment，如果没有则创建EmptyFragment并添加到Activity中
 * @receiver FragmentManager
 * @return EmptyFragment 已创建并已添加到Activity中的Fragment
 */
private fun findOrCreateEmptyFragment(manager: FragmentManager): EmptyFragment {
    return manager.findFragmentByTag(FRAGMENT_TAG) as? EmptyFragment ?: EmptyFragment().also {
        manager.beginTransaction()
            .replace(android.R.id.content, it, FRAGMENT_TAG)
//            .add(it, FRAGMENT_TAG)
            .commitNowAllowingStateLoss()
    }
}

private fun <M : Map<Int, *>> codeGenerate(map: M): Int {
    var requestCode: Int
    do {
        requestCode = codeGenerator.nextInt(0xFFFF)
    } while (requestCode in map.keys)
    return requestCode
}

/**
 * 启动Activity并接收Intent的扩展方法，接收回调时不需要重写[Activity#onActivityResult]方法
 * @receiver F  基于[FragmentActivity]的扩展方法
 * @param intent Intent [#startActivity]必需的参数
 * @param options Bundle?   动画参数
 * @param callback (data: Intent) -> Unit   返回此界面时，当[#resultCode]为 RESULT_OK时的回调
 * @return LambdaHolder<Intent> 可以在此对象上继续调用 [LambdaHolder#onCanceled]或
 *      [LambdaHolder#onDefined] 方法来设置 ResultCode 为 RESULT_CANCELED 或 RESULT_FIRST_USER 时的回调
 */
fun <F : FragmentActivity> F.startActivityForResult(
    intent: Intent,
    options: Bundle? = null,
    callback: (Intent) -> Unit = {}
): LambdaHolder<Intent> {
    val requestCode = codeGenerate(resultHolder)
    val emptyFragment = findOrCreateEmptyFragment(supportFragmentManager)
    return emptyFragment.startActivityForResult(requestCode, intent, options, callback)
}

/**
 * 申请权限的扩展方法，通过lambda传入回调，不需要重写[Activity#onRequestPermissionsResult]方法
 * @receiver F  基于[FragmentActivity]的扩展方法
 * @param permission Array<out String>  要申请的权限
 * @param onRequestDone () -> Unit  申请成功时的回调
 * @return LambdaHolder<Unit>   可以在此对象上继续调用[#onDenied]方法来设置申请失败时的回调
 */
fun <F : FragmentActivity> F.requestPermissions(
    vararg permission: String,
    onRequestDone: () -> Unit
): LambdaHolder<Unit> {
    //获取一个与已有编码不重复的编码
    val requestCode = codeGenerate(permissionHolder)
    //查找Activity中有没有空的Fragment，如果没有则创建空的Fragment并添加到Activity中
    val emptyFragment = findOrCreateEmptyFragment(supportFragmentManager)
    //使用Fragment的requestPermissions方法申请权限并传入回调
    return emptyFragment.requestPermissions(requestCode, *permission) {
        onRequestDone()
    }
}

class EmptyFragment : Fragment() {
    fun startActivityForResult(
        requestCode: Int,
        intent: Intent?,
        options: Bundle? = null,
        callback: (Intent) -> Unit
    ): LambdaHolder<Intent> {
        return LambdaHolder(callback).also {
            resultHolder[requestCode] = it
            startActivityForResult(intent, requestCode, options)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        //取出与requestCode对应的对象，然后执行与resultCode对应的回调
        resultHolder.remove(requestCode)?.let {
            it.before()
            when (resultCode) {
                Activity.RESULT_OK -> it.onSuccess(data ?: Intent())
                Activity.RESULT_CANCELED -> it.onCanceled()
                else -> it.onDefined(data)
            }
        }
    }

    fun requestPermissions(
        requestCode: Int,
        vararg permissions: String,
        onRequestDone: (Unit) -> Unit
    ): LambdaHolder<Unit> {
        return LambdaHolder(onRequestDone).also {
            if (Build.VERSION.SDK_INT >= 23 && !checkPermissions(*permissions)) {
                permissionHolder[requestCode] = it
                requestPermissions(permissions, requestCode)
            } else {
                it.onSuccess(Unit)
            }
        }
    }

    private fun checkPermissions(vararg permission: String): Boolean {
        return permission.all {
            ActivityCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val lambdaHolder = permissionHolder.remove(requestCode) ?: return
        //当有正在申请的权限未结束时，permissions和grantResults会是空的，此时为申请失败，做中断处理
        if (permissions.isEmpty() && grantResults.isEmpty()) return
        //将未授予的权限加入到一个列表中
        grantResults.toList().mapIndexedNotNull { index, result ->
            if (result != PackageManager.PERMISSION_GRANTED) permissions[index] else null
        }.let {
            //通过列表是否为空来判断权限是否授予，然后执行对应的回调
            if (it.isEmpty()) lambdaHolder.onSuccess(Unit) else lambdaHolder.onDenied(it)
        }
    }
}