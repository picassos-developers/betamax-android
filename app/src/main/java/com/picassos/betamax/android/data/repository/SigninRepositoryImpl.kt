package com.picassos.betamax.android.data.repository

import com.picassos.betamax.android.core.resource.Resource
import com.picassos.betamax.android.data.mapper.toAccount
import com.picassos.betamax.android.data.source.remote.APIService
import com.picassos.betamax.android.domain.model.Account
import com.picassos.betamax.android.domain.repository.SigninRepository
import com.picassos.betamax.android.core.utilities.Response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SigninRepositoryImpl @Inject constructor(private val service: APIService): SigninRepository {
    override suspend fun signin(imei: String, email: String, password: String): Flow<Resource<Account>> {
        return flow {
            emit(Resource.Loading(true))
            try {
                val response = service.signin(
                    imei = imei,
                    email = email,
                    password = password)
                emit(Resource.Success(response.toAccount()))
            } catch (t: Throwable) {
                when (t) {
                    is IOException -> emit(Resource.Error(Response.NETWORK_FAILURE_EXCEPTION))
                    else -> emit(Resource.Error(Response.MALFORMED_REQUEST_EXCEPTION))
                }
            }
            emit(Resource.Loading(false))
        }
    }
}